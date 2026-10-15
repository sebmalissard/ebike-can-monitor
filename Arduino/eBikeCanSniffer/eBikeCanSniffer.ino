// Custom partitions is required to use max FW size (1,875 MB) with OTA enable
// Add in boards.txt (C:\Users\%UserProfile%\AppData\Local\Arduino15\packages\esp32\hardware\esp32\3.3.10):
//   XIAO_ESP32C6.menu.PartitionScheme.custom=Custom
//   XIAO_ESP32C6.menu.PartitionScheme.custom.build.custom_partitions=partitions
//   XIAO_ESP32C6.menu.PartitionScheme.custom.upload.maximum_size=1966080
// Close IDE and clear cache (C:\Users\%UserProfile%\AppData\Roaming\arduino-ide)

#include <ArduinoOTA.h>
#include <ESPmDNS.h>
#include <WiFi.h>
#include <WiFiUdp.h>

#include <driver/twai.h>
#include <esp_timer.h>

#include "BLESerial.h"
#include "Credentials.h"

// Pinmux XIAO ESP32-C6:
//  D0: TXD --> WCMCU-230(SN65HVD230): CTX
//  D1: RXD --> WCMCU-230(SN65HVD230): CRX

#define CAN_TX_PIN GPIO_NUM_0   // D0
#define CAN_RX_PIN GPIO_NUM_1   // D1

BLESerial ble("eBikeCanSniffer");
bool wifiOTASetup = false;

void setupWifiOTA() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  
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
  }
  Serial.println("\nWi-Fi connected.");
  Serial.print("IP address: ");
  Serial.println(WiFi.localIP());

  // Arduino OTA
  ArduinoOTA.setHostname("ESP32-eBikeCanSniffer-OTA");
  ArduinoOTA.setPassword(ARDUINO_OTA_PASSWORD);

  ArduinoOTA.onStart([]() {
    Serial.println("OA: Start");
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
    Serial.println("Driver TWAI installe avec succes");
  } else {
    Serial.println("Erreur d'installation du driver TWAI");
    while (1) delay(1000);
  }

  if (twai_start() == ESP_OK) {
    Serial.println("TWAI demarre avec succes");
  } else {
    Serial.println("Erreur au demarrage du TWAI");
    while (1) delay(1000);
  }

  Serial.println("En attente de messages CAN...");
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
//            (before the BLE send): a gap means lost frames
//     ts_us  esp_timer_get_time() taken right after the TWAI read (µs since boot)
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

static uint32_t canSeq = 0;
static unsigned long lastStatusMs = 0;

void sendCanFrame(const twai_message_t& m, uint32_t seq, int64_t tsUs) {
  char line[96];
  int n = snprintf(line, sizeof(line), "%lu,%llu,", (unsigned long)seq, (unsigned long long)tsUs);

  if (m.extd) {
    n += snprintf(line + n, sizeof(line) - n, "%08lX,E", (unsigned long)m.identifier);
  } else {
    n += snprintf(line + n, sizeof(line) - n, "%03lX,S", (unsigned long)m.identifier);
  }
  if (m.rtr) {
    n += snprintf(line + n, sizeof(line) - n, "R");
  }

  n += snprintf(line + n, sizeof(line) - n, ",%u,", (unsigned)m.data_length_code);

  if (!m.rtr) {
    uint8_t len = m.data_length_code > TWAI_FRAME_MAX_DLC ? TWAI_FRAME_MAX_DLC : m.data_length_code;
    for (uint8_t i = 0; i < len; i++) {
      n += snprintf(line + n, sizeof(line) - n, "%02X", m.data[i]);
    }
  }

  n += snprintf(line + n, sizeof(line) - n, "\n");
  ble.send(line, n);
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
  twai_message_t rxMsg;

  if (twai_receive(&rxMsg, pdMS_TO_TICKS(100)) == ESP_OK) {
    int64_t tsUs = esp_timer_get_time();
    sendCanFrame(rxMsg, canSeq++, tsUs);
  }

  unsigned long now = millis();
  if (now - lastStatusMs >= STATUS_PERIOD_MS) {
    lastStatusMs = now;
    sendStatus();
  }

  if (wifiOTASetup) {
    ArduinoOTA.handle();
  }
}
