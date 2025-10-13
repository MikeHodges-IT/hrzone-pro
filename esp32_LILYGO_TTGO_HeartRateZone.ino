#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <TFT_eSPI.h>
#include <SPI.h>

// TTGO T-Display pins are predefined in TFT_eSPI User_Setup_Select.h
// Make sure to select the correct board in the library settings
// or define #define USER_SETUP_ID 25 in User_Setup_Select.h

// BLE Settings
#define DEVICE_NAME "HRZone"
#define SERVICE_UUID        "12345678-1234-1234-1234-123456789abc"
#define HEART_RATE_CHAR_UUID "12345678-1234-1234-1234-123456789001"

// Display object
TFT_eSPI tft = TFT_eSPI();

// BLE variables
BLEServer* pServer = nullptr;
BLECharacteristic* pHeartRateCharacteristic = nullptr;
bool deviceConnected = false;
int currentHeartRate = 0;
unsigned long lastDisplayUpdate = 0;

// Display dimensions for TTGO T-Display
#define SCREEN_WIDTH 135
#define SCREEN_HEIGHT 240

// Display colors (TFT_eSPI format)
#define COLOR_BACKGROUND TFT_BLACK
#define COLOR_HEART_NORMAL TFT_RED
#define COLOR_HEART_HIGH TFT_YELLOW
#define COLOR_TEXT TFT_WHITE
#define COLOR_CONNECTED TFT_GREEN
#define COLOR_DISCONNECTED TFT_BLUE

// Forward declarations
void updateConnectionStatus();
void updateHeartRateDisplay();
void displayStartupScreen();
void setupBLE();
void drawHeart(int x, int y, uint16_t color);
void drawHeartRateZone(int bpm);

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
      deviceConnected = true;
      Serial.println("Wear OS connected!");
      updateConnectionStatus();
    };

    void onDisconnect(BLEServer* pServer) {
      deviceConnected = false;
      currentHeartRate = 0;
      Serial.println("Wear OS disconnected!");
      updateConnectionStatus();
    }
};

class HeartRateCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String value = pCharacteristic->getValue();
      
      if (value.length() > 0) {
        Serial.print("Received: ");
        Serial.println(value);
        
        // Parse heart rate from "HR:75" format
        if (value.startsWith("HR:")) {
          currentHeartRate = value.substring(3).toInt();
          Serial.printf("Heart Rate: %d BPM\n", currentHeartRate);
          updateHeartRateDisplay();
        }
      }
    }
};

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n\n=== ESP32 Heart Monitor Starting ===");
  Serial.print("Device Name: ");
  Serial.println(DEVICE_NAME);
  Serial.print("Service UUID: ");
  Serial.println(SERVICE_UUID);
  
  // Initialize display
  tft.init();
  tft.setRotation(0); // Portrait mode
  tft.fillScreen(COLOR_BACKGROUND);
  
  // Optional: Set brightness (if supported)
  // ledcSetup(0, 5000, 8);
  // ledcAttachPin(4, 0); // Backlight pin on TTGO T-Display
  // ledcWrite(0, 255); // Full brightness
  
  // Show startup screen
  displayStartupScreen();
  
  // Initialize BLE
  setupBLE();
  
  Serial.println("=== BLE Advertising Started ===");
  Serial.println("Waiting for connections...");
  Serial.println("System ready!");
}

void setupBLE() {
  BLEDevice::init(DEVICE_NAME);
  
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  
  BLEService *pService = pServer->createService(SERVICE_UUID);
  
  pHeartRateCharacteristic = pService->createCharacteristic(
                               HEART_RATE_CHAR_UUID,
                               BLECharacteristic::PROPERTY_READ |
                               BLECharacteristic::PROPERTY_WRITE
                             );
  pHeartRateCharacteristic->setCallbacks(new HeartRateCallbacks());
  pHeartRateCharacteristic->setValue("0");
  
  pService->start();
  
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(false);
  pAdvertising->setMinPreferred(0x0);
  BLEDevice::startAdvertising();
}

void displayStartupScreen() {
  tft.fillScreen(COLOR_BACKGROUND);
  
  // Draw title
  tft.setTextColor(COLOR_TEXT, COLOR_BACKGROUND);
  tft.setTextSize(2);
  
  // Center text calculations for narrow screen
  String line1 = "Heart";
  String line2 = "Rate";
  String line3 = "Monitor";
  
  int16_t x1 = (SCREEN_WIDTH - tft.textWidth(line1)) / 2;
  int16_t x2 = (SCREEN_WIDTH - tft.textWidth(line2)) / 2;
  int16_t x3 = (SCREEN_WIDTH - tft.textWidth(line3)) / 2;
  
  tft.setCursor(x1, 60);
  tft.println(line1);
  tft.setCursor(x2, 80);
  tft.println(line2);
  tft.setCursor(x3, 100);
  tft.println(line3);
  
  // Draw waiting message
  tft.setTextSize(1);
  String wait1 = "Waiting for";
  String wait2 = "Wear OS...";
  
  x1 = (SCREEN_WIDTH - tft.textWidth(wait1)) / 2;
  x2 = (SCREEN_WIDTH - tft.textWidth(wait2)) / 2;
  
  tft.setCursor(x1, 160);
  tft.println(wait1);
  tft.setCursor(x2, 175);
  tft.println(wait2);
}

void updateConnectionStatus() {
  // Draw connection indicator at top
  tft.fillCircle(SCREEN_WIDTH/2, 15, 6, deviceConnected ? COLOR_CONNECTED : COLOR_DISCONNECTED);
  
  if (!deviceConnected) {
    // Clear heart rate area when disconnected
    tft.fillRect(10, 90, SCREEN_WIDTH-20, 80, COLOR_BACKGROUND);
    tft.setTextColor(COLOR_TEXT, COLOR_BACKGROUND);
    tft.setTextSize(3);
    
    String dashes = "---";
    int16_t xPos = (SCREEN_WIDTH - tft.textWidth(dashes)) / 2;
    
    tft.setCursor(xPos, 120);
    tft.println(dashes);
  }
}

void updateHeartRateDisplay() {
  // Clear previous heart rate display area
  tft.fillRect(10, 50, SCREEN_WIDTH-20, 120, COLOR_BACKGROUND);
  
  // Draw heart symbol
  drawHeart(SCREEN_WIDTH/2, 60, currentHeartRate > 100 ? COLOR_HEART_HIGH : COLOR_HEART_NORMAL);
  
  // Display BPM value
  tft.setTextColor(COLOR_TEXT, COLOR_BACKGROUND);
  tft.setTextSize(4);
  
  // Center the BPM text
  String bpmText = String(currentHeartRate);
  int16_t xPos = (SCREEN_WIDTH - tft.textWidth(bpmText)) / 2;
  
  tft.setCursor(xPos, 90);
  tft.print(currentHeartRate);
  
  // Display "BPM" label
  tft.setTextSize(2);
  String bpmLabel = "BPM";
  xPos = (SCREEN_WIDTH - tft.textWidth(bpmLabel)) / 2;
  
  tft.setCursor(xPos, 135);
  tft.println(bpmLabel);
  
  // Draw heart rate zones
  drawHeartRateZone(currentHeartRate);
}

void drawHeart(int x, int y, uint16_t color) {
  // Draw a simple heart shape (scaled for smaller screen)
  tft.fillCircle(x - 6, y, 6, color);
  tft.fillCircle(x + 6, y, 6, color);
  tft.fillTriangle(x - 12, y + 3, x + 12, y + 3, x, y + 15, color);
}

void drawHeartRateZone(int bpm) {
  // Draw zone indicator at bottom
  String zone;
  uint16_t zoneColor;
  
  if (bpm < 60) {
    zone = "Low";
    zoneColor = TFT_BLUE;
  } else if (bpm < 100) {
    zone = "Normal";
    zoneColor = TFT_GREEN;
  } else if (bpm < 140) {
    zone = "Elevated";
    zoneColor = TFT_YELLOW;
  } else {
    zone = "High";
    zoneColor = TFT_RED;
  }
  
  // Clear zone area
  tft.fillRect(20, 190, SCREEN_WIDTH-40, 30, COLOR_BACKGROUND);
  
  // Draw zone background
  tft.fillRoundRect(20, 195, SCREEN_WIDTH-40, 25, 5, zoneColor);
  
  // Draw zone text
  tft.setTextColor(COLOR_BACKGROUND, zoneColor);
  tft.setTextSize(2);
  
  int16_t xPos = (SCREEN_WIDTH - tft.textWidth(zone)) / 2;
  tft.setCursor(xPos, 200);
  tft.print(zone);
}

void loop() {
  // Restart advertising if disconnected
  if (!deviceConnected) {
    static unsigned long lastAdvertise = 0;
    if (millis() - lastAdvertise > 500) {
      pServer->startAdvertising();
      lastAdvertise = millis();
    }
  }
  
  // Optional: Add animation or other display updates
  delay(100);
}