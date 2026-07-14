#include <driver/twai.h>

#include "BLESerial.h"

// Pinmux XIAO ESP32-C6:
//  D0: TXD --> WCMCU-230(SN65HVD230): CTX
//  D1: RXD --> WCMCU-230(SN65HVD230): CRX

#define CAN_TX_PIN GPIO_NUM_0   // D0
#define CAN_RX_PIN GPIO_NUM_1   // D1

BLESerial ble("eBikeCanSniffer");

void setup() {
  Serial.begin(115200);
  unsigned long t0 = millis();
  while (!Serial && millis() - t0 < 3000); // attend le port série (timeout 3s)

  ble.begin();

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

void loop() {
  twai_message_t rxMsg;

  if (twai_receive(&rxMsg, pdMS_TO_TICKS(100)) == ESP_OK) {
    String line = "ID: 0x" + String(rxMsg.identifier, HEX);

    if (rxMsg.extd) line += " (extended)";
    if (rxMsg.rtr)  line += " (RTR)";

    line += "  DLC: " + String(rxMsg.data_length_code) + "  Data: ";

    for (int i = 0; i < rxMsg.data_length_code; i++) {
      if (rxMsg.data[i] < 0x10) line += "0";
      line += String(rxMsg.data[i], HEX) + " ";
    }
    line += "\n";

    ble.send(line);
  }
}
