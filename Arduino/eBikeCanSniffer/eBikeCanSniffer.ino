#include "driver/twai.h"

// === Brochage XIAO ESP32-C6 <-> WCMCU-230 (SN65HVD230) ===
// Le WCMCU-230 est un simple transceiver CAN.
// L'ESP32-C6 a un contrôleur CAN (TWAI) intégré, il faut juste relier 2 GPIO
// aux broches TXD/RXD du module.
//
//   XIAO ESP32-C6      WCMCU-230
//   --------------      ---------
//   D0 (GPIO0)   --->   TXD (D)      (sortie MCU vers module - non utilisee
//                                     en pratique en mode Listen Only, mais
//                                     doit quand meme etre cablee/definie)
//   D1 (GPIO1)   <---   RXD (R)      (entrée MCU depuis module)
//   3V3          --->   VCC (module 3.3V natif, pas besoin de level shifter)
//   GND          --->   GND
//
//   CANH/CANL du module -> bus CAN (avec résistances de terminaison 120 ohm
//   aux deux extrémités du bus)

#define CAN_TX_PIN GPIO_NUM_0   // D0
#define CAN_RX_PIN GPIO_NUM_1   // D1

void setup() {
  Serial.begin(115200);
  unsigned long t0 = millis();
  while (!Serial && millis() - t0 < 3000); // attend le port série (timeout 3s)

  // Configuration générale : mode Listen Only (sniffer pur)
  // Dans ce mode, l'ESP32-C6 n'emet JAMAIS rien sur le bus, meme pas un ACK.
  // Ideal pour un sniffer branche au milieu d'un bus CAN deja actif,
  // sans risque de perturber la communication existante.
  twai_general_config_t g_config = TWAI_GENERAL_CONFIG_DEFAULT(
      CAN_TX_PIN, CAN_RX_PIN, TWAI_MODE_LISTEN_ONLY);

  // Vitesse du bus : 250 kbps (à adapter selon ton réseau CAN)
  twai_timing_config_t t_config = TWAI_TIMING_CONFIG_250KBITS();

  // Filtre : on accepte tous les messages
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

  // Attend un message pendant max 100 ms, puis revient boucler
  // (permet de laisser la main au reste du programme si besoin)
  if (twai_receive(&rxMsg, pdMS_TO_TICKS(100)) == ESP_OK) {
    Serial.print("ID: 0x");
    Serial.print(rxMsg.identifier, HEX);

    if (rxMsg.extd) {
      Serial.print(" (extended)");
    }
    if (rxMsg.rtr) {
      Serial.print(" (RTR)");
    }

    Serial.print("  DLC: ");
    Serial.print(rxMsg.data_length_code);
    Serial.print("  Data: ");

    for (int i = 0; i < rxMsg.data_length_code; i++) {
      if (rxMsg.data[i] < 0x10) Serial.print("0");
      Serial.print(rxMsg.data[i], HEX);
      Serial.print(" ");
    }
    Serial.println();
  }

  // Optionnel : surveiller les erreurs/alertes du bus
  // twai_status_info_t status;
  // twai_get_status_info(&status);
}
