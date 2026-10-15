// Custom partitions is required to use max FW size (1,875 MB) with OTA enable
// Add in boards.txt (C:\Users\%UserProfile%\AppData\Local\Arduino15\packages\esp32\hardware\esp32\3.3.10):
//   XIAO_ESP32C6.menu.PartitionScheme.custom=Custom
//   XIAO_ESP32C6.menu.PartitionScheme.custom.build.custom_partitions=partitions
//   XIAO_ESP32C6.menu.PartitionScheme.custom.upload.maximum_size=1966080
// Close IDE and clear cache (C:\Users\%UserProfile%\AppData\Roaming\arduino-ide)

#include <ArduinoOTA.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <WiFi.h>
#include <WiFiUdp.h>

#include <driver/twai.h>
#include <esp_timer.h>

#include "BLESerial.h"
#include "CanRingBuffer.h"

// Pinmux XIAO ESP32-C6:
//  D0: TXD --> WCMCU-230(SN65HVD230): CTX
//  D1: RXD --> WCMCU-230(SN65HVD230): CRX

#define CAN_TX_PIN GPIO_NUM_0   // D0
#define CAN_RX_PIN GPIO_NUM_1   // D1

BLESerial ble("eBikeCanSniffer");
bool wifiOTASetup = false;

static const char* CREDENTIALS_NVS_PARTITION = "fctry";
static const char* WIFI_NVS_NAMESPACE = "wifi";
static const char* OTA_NVS_NAMESPACE = "ota";

String loadCredential(const char* nvsNamespace, const char* key) {
  Preferences prefs;
  prefs.begin(nvsNamespace, true, CREDENTIALS_NVS_PARTITION);  // read-only
  String value = prefs.getString(key, "");
  prefs.end();
  return value;
}

void setupWifiOTA() {
  String ssid = loadCredential(WIFI_NVS_NAMESPACE, "ssid");
  String password = loadCredential(WIFI_NVS_NAMESPACE, "pass");
  String otaPassword = loadCredential(OTA_NVS_NAMESPACE, "pass");

  if (ssid.length() == 0) {
    Serial.println("No Wi-Fi credentials in fctry partition, Wi-Fi OTA disabled.");
    return;
  }

  // Never run OTA without a password
  if (otaPassword.length() == 0) {
    Serial.println("No OTA password in fctry partition, Wi-Fi OTA disabled.");
    return;
  }

  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid.c_str(), password.c_str());
  
  // Wifi connection (5s timeout)
  Serial.println("Connecting to Wifi:");
  int attempts = 0;
  while (WiFi.status() != WL_CONNECTED && attempts < 10) {
    delay(500);
    Serial.print(".");
    attempts++;
  }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("\nWifi connection fail, abort Wifi OTA setup");
    return;
  }
  Serial.println("\nWi-Fi connected.");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  // Arduino OTA
  ArduinoOTA.setHostname("ESP32-eBikeCanSniffer-OTA");
  ArduinoOTA.setPassword(otaPassword.c_str());

  ArduinoOTA.onStart([]() {
    Serial.println("OTA: Start");
  });
  
  ArduinoOTA.onEnd([]() {
    Serial.println("\nOTA: End");
  });
  
  ArduinoOTA.onProgress([](unsigned int progress, unsigned int total) {
    Serial.printf("OTA: Progress: %u%%\r", (progress / (total / 100)));
  });
  
  ArduinoOTA.onError([](ota_error_t error) {
    Serial.printf("OTA: ERROR [%u]: ", error);
    switch (error) {
      case OTA_AUTH_ERROR:    Serial.println("OTA_AUTH_ERROR");     break;
      case OTA_BEGIN_ERROR:   Serial.println("OTA_BEGIN_ERROR");    break;
      case OTA_CONNECT_ERROR: Serial.println("OTA_CONNECT_ERROR");  break;
      case OTA_RECEIVE_ERROR: Serial.println("OTA_RECEIVE_ERROR");  break;
      case OTA_END_ERROR:     Serial.println("OTA_END_ERROR");      break;
    }
  });

  ArduinoOTA.begin();

  wifiOTASetup = true;
}

void setupCAN() {
  twai_general_config_t g_config = TWAI_GENERAL_CONFIG_DEFAULT(
      CAN_TX_PIN, CAN_RX_PIN, TWAI_MODE_LISTEN_ONLY);

  twai_timing_config_t t_config = TWAI_TIMING_CONFIG_250KBITS();
  twai_filter_config_t f_config = TWAI_FILTER_CONFIG_ACCEPT_ALL();

  if (twai_driver_install(&g_config, &t_config, &f_config) == ESP_OK) {
    Serial.println("TWAI driver installed successfully");
  } else {
    Serial.println("TWAI driver installation failed");
    while (1) delay(1000);
  }

  if (twai_start() == ESP_OK) {
    Serial.println("TWAI started successfully");
  } else {
    Serial.println("TWAI start failed");
    while (1) delay(1000);
  }

  Serial.println("Waiting for CAN messages...");
}

void setup() {
  Serial.begin(115200);
  unsigned long t0 = millis();
  while (!Serial && millis() - t0 < 3000);

  setupWifiOTA();

  ble.begin();

  setupCAN();
}

// ---------------------------------------------------------------------------
// BLE text protocol (one line per message, terminated by '\n')
//
//   CAN frame: seq,ts_us,id,flags,dlc,data
//     seq    uint32 counter incremented for every frame read from the TWAI
//            (before buffering and the BLE send): a gap means lost frames
//     ts_us  esp_timer_get_time() taken right after the TWAI read (µs since boot),
//            kept as is while the frame waits in the ring buffer
//     id     identifier in uppercase hex (3 digits if standard, 8 if extended)
//     flags  S (standard) or E (extended), followed by R for RTR frames
//     dlc    data_length_code (decimal)
//     data   data bytes as concatenated uppercase hex, empty for an RTR frame
//     e.g.: 42,12345678,1A2,E,8,01FF00AA11223344
//
//   TWAI status: #STATUS,missed=N,overrun=N  (cumulative driver counters,
//     sent every STATUS_PERIOD_MS)
// ---------------------------------------------------------------------------

static const unsigned long STATUS_PERIOD_MS = 5000;

// CAN frames wait here until they can be sent over BLE: the BLE client usually
// connects after the first CAN frames have been received. When the buffer is
// full, the oldest frames are overwritten.
// Memory: RING_CAPACITY * sizeof(CanRecord) = 2048 * 32 bytes = 64 KB.
static const size_t RING_CAPACITY = 2048;
static CanRingBuffer<RING_CAPACITY> canRing;

// Max payload of one BLE send (largest MTU is 247 => 240 bytes of payload).
static const size_t BLE_BATCH_SIZE = 240;
// Upper bound for one formatted frame line (seq 10 + ts 20 + id 8 + flags 2 +
// dlc 1 + data 16 + 5 commas + '\n' = 63).
static const size_t MAX_LINE_SIZE = 64;
// Limit work done per loop() iteration so new frames keep being read.
static const int MAX_BATCHES_PER_LOOP = 4;

static uint32_t canSeq = 0;
static unsigned long lastStatusMs = 0;

CanRecord makeCanRecord(const twai_message_t& m, uint32_t seq, int64_t tsUs) {
  CanRecord rec = {};
  rec.tsUs = tsUs;
  rec.seq = seq;
  rec.id = m.identifier;
  rec.flags = (m.extd ? CAN_RECORD_FLAG_EXT : 0) | (m.rtr ? CAN_RECORD_FLAG_RTR : 0);
  rec.dlc = m.data_length_code;
  if (!m.rtr) {
    uint8_t len = m.data_length_code > TWAI_FRAME_MAX_DLC ? TWAI_FRAME_MAX_DLC : m.data_length_code;
    memcpy(rec.data, m.data, len);
  }
  return rec;
}

// Format one record as a protocol line. Returns the number of bytes written.
size_t formatCanRecord(char* out, size_t size, const CanRecord& r) {
  const bool ext = r.flags & CAN_RECORD_FLAG_EXT;
  const bool rtr = r.flags & CAN_RECORD_FLAG_RTR;

  size_t n = snprintf(out, size, "%lu,%llu,", (unsigned long)r.seq, (unsigned long long)r.tsUs);

  if (ext) {
    n += snprintf(out + n, size - n, "%08lX,E", (unsigned long)r.id);
  } else {
    n += snprintf(out + n, size - n, "%03lX,S", (unsigned long)r.id);
  }
  if (rtr) {
    n += snprintf(out + n, size - n, "R");
  }

  n += snprintf(out + n, size - n, ",%u,", (unsigned)r.dlc);

  if (!rtr) {
    uint8_t len = r.dlc > TWAI_FRAME_MAX_DLC ? TWAI_FRAME_MAX_DLC : r.dlc;
    for (uint8_t i = 0; i < len; i++) {
      n += snprintf(out + n, size - n, "%02X", r.data[i]);
    }
  }

  n += snprintf(out + n, size - n, "\n");
  return n;
}

// Send buffered frames over BLE, several lines per notification.
void drainCanRing() {
  char batch[BLE_BATCH_SIZE];
  CanRecord rec;

  for (int i = 0; i < MAX_BATCHES_PER_LOOP && ble.isConnected(); i++) {
    size_t len = 0;
    while (len + MAX_LINE_SIZE <= sizeof(batch) && canRing.peek(rec)) {
      len += formatCanRecord(batch + len, sizeof(batch) - len, rec);
      canRing.pop();
    }
    if (len == 0) break;
    ble.send(batch, len);
  }
}

void sendStatus() {
  twai_status_info_t st;
  if (twai_get_status_info(&st) != ESP_OK) return;

  char line[64];
  int n = snprintf(line, sizeof(line), "#STATUS,missed=%lu,overrun=%lu\n",
                   (unsigned long)st.rx_missed_count, (unsigned long)st.rx_overrun_count);
  ble.send(line, n);
}

void loop() {
  // Do not wait for new frames while buffered frames are waiting to be sent
  const bool backlog = ble.isConnected() && !canRing.empty();
  const TickType_t timeout = backlog ? 0 : pdMS_TO_TICKS(100);

  twai_message_t rxMsg;
  if (twai_receive(&rxMsg, timeout) == ESP_OK) {
    int64_t tsUs = esp_timer_get_time();
    canRing.push(makeCanRecord(rxMsg, canSeq++, tsUs));
  }

  drainCanRing();

  unsigned long now = millis();
  if (now - lastStatusMs >= STATUS_PERIOD_MS) {
    lastStatusMs = now;
    sendStatus();
  }

  if (wifiOTASetup) {
    ArduinoOTA.handle();
  }
}
