/*
 * M5HRZ - M5Stack Core2 Heart Rate Monitor with Clean Display
 * Shows debug info only when there are problems
 */

#include <M5Unified.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// BLE Settings
#define DEVICE_NAME "HRZone"
#define SERVICE_UUID        "12345678-1234-1234-1234-123456789abc"
#define HEART_RATE_CHAR_UUID "12345678-1234-1234-1234-123456789001"

// Display dimensions
#define SCREEN_WIDTH 320
#define SCREEN_HEIGHT 240

// Zone definitions
const int ZONE_COUNT = 5;
const int ZONE_THRESHOLDS[] = {0, 60, 100, 130, 160, 200};
const char* ZONE_NAMES[] = {"REST", "NORMAL", "FAT BURN", "CARDIO", "PEAK"};
const uint16_t ZONE_COLORS[] = {
    TFT_CYAN,      // Rest
    TFT_GREEN,     // Normal  
    TFT_YELLOW,    // Fat Burn
    TFT_ORANGE,    // Cardio
    TFT_RED        // Peak
};

// Graph data
#define GRAPH_POINTS 120
int heartRateHistory[GRAPH_POINTS];
int historyIndex = 0;
unsigned long lastHistoryUpdate = 0;

// BLE variables
BLEServer* pServer = nullptr;
BLECharacteristic* pHeartRateCharacteristic = nullptr;
bool deviceConnected = false;
bool lastConnected = false;
int currentHeartRate = 0;
int displayedHeartRate = -1;
int currentZone = 0;
bool waitingScreenDrawn = false;

// Debug tracking
unsigned long lastDataReceived = 0;
unsigned long connectionStartTime = 0;
int dataGapSeconds = 0;
int totalPacketsReceived = 0;
int packetsThisSession = 0;
int maxGapSeconds = 0;
int consecutiveFailures = 0;
unsigned long lastAdvertiseTime = 0;
String lastReceivedValue = "";
bool showDebugInfo = false;

class MyServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        connectionStartTime = millis();
        packetsThisSession = 0;
        consecutiveFailures = 0;
        waitingScreenDrawn = false;
        Serial.println("=== Wear OS CONNECTED ===");
        Serial.printf("Connection time: %lu\n", connectionStartTime);
    }

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        currentHeartRate = 0;
        lastDataReceived = 0;
        waitingScreenDrawn = false;
        unsigned long sessionDuration = (millis() - connectionStartTime) / 1000;
        Serial.println("=== Wear OS DISCONNECTED ===");
        Serial.printf("Session duration: %lu seconds\n", sessionDuration);
        Serial.printf("Packets received: %d\n", packetsThisSession);
        Serial.printf("Max gap: %d seconds\n", maxGapSeconds);
    }
};

class HeartRateCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
        String value = pCharacteristic->getValue();
        
        if (value.length() > 0) {
            unsigned long now = millis();
            if (lastDataReceived > 0) {
                dataGapSeconds = (now - lastDataReceived) / 1000;
                if (dataGapSeconds > maxGapSeconds) {
                    maxGapSeconds = dataGapSeconds;
                }
                
                if (dataGapSeconds > 5) {
                    consecutiveFailures++;
                    Serial.printf("WARNING: Data gap of %d seconds (failure #%d)\n", 
                                dataGapSeconds, consecutiveFailures);
                } else {
                    consecutiveFailures = 0;
                }
            }
            
            lastDataReceived = now;
            lastReceivedValue = value;
            totalPacketsReceived++;
            packetsThisSession++;
            
            Serial.printf("[%lu] Received: %s (gap: %ds, total: %d)\n", 
                        now, value.c_str(), dataGapSeconds, totalPacketsReceived);
            
            if (value.startsWith("HR:")) {
                currentHeartRate = value.substring(3).toInt();
                Serial.printf("Heart Rate: %d BPM\n", currentHeartRate);
            }
        }
    }
};

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== HRZone Monitor Starting (v2.5) ===");
    Serial.println("Clean display with enhanced visuals");
    
    // Initialize heart rate history
    for (int i = 0; i < GRAPH_POINTS; i++) {
        heartRateHistory[i] = 0;
    }
    
    // Initialize M5 with custom config
    auto cfg = M5.config();
    cfg.external_imu = false;
    cfg.external_rtc = false;
    cfg.internal_imu = false;
    cfg.internal_rtc = false;
    
    M5.begin(cfg);
    M5.Display.fillScreen(TFT_BLACK);
    M5.Display.setTextDatum(middle_center);
    
    // Setup BLE
    setupBLE();
    
    // Draw initial interface
    drawStaticElements();
    updateDisplay();
    
    Serial.println("Ready! Waiting for Wear OS connection...");
}

void setupBLE() {
    Serial.println("Initializing BLE...");
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
    
    Serial.println("BLE advertising started as 'HRZone'");
}

void drawStaticElements() {
    M5.Display.fillScreen(TFT_BLACK);
    drawColorBar();
}

void drawColorBar() {
    int barY = 10;
    int barHeight = 15;
    int barWidth = SCREEN_WIDTH - 20;
    int barX = 10;
    
    // Draw each zone color segment proportional to actual BPM ranges
    int zoneStarts[] = {0, 60, 100, 130, 160};
    int zoneEnds[] = {60, 100, 130, 160, 200};
    
    for (int i = 0; i < 5; i++) {
        int segmentStart = barX + (zoneStarts[i] * barWidth / 200);
        int segmentEnd = barX + (zoneEnds[i] * barWidth / 200);
        int segmentWidth = segmentEnd - segmentStart;
        
        M5.Display.fillRect(segmentStart, barY, segmentWidth, barHeight, ZONE_COLORS[i]);
    }
    
    // Draw zone boundary markers
    for (int i = 1; i < 5; i++) {
        int markerX = barX + (zoneStarts[i] * barWidth / 200);
        M5.Display.drawLine(markerX, barY, markerX, barY + barHeight - 1, TFT_WHITE);
    }
    
    // Draw border around bar
    M5.Display.drawRect(barX - 1, barY - 1, barWidth + 2, barHeight + 2, TFT_WHITE);
    
    // Add BPM labels
    M5.Display.setTextSize(1);
    M5.Display.setTextColor(TFT_DARKGREY);
    M5.Display.setTextDatum(top_center);
    
    int labelBPMs[] = {60, 100, 130, 160};
    for (int i = 0; i < 4; i++) {
        int labelX = barX + (labelBPMs[i] * barWidth / 200);
        M5.Display.drawString(String(labelBPMs[i]), labelX, 1);
    }
}

void drawMarkerOnBar(int bpm) {
    int barY = 10;
    int barHeight = 15;
    int barWidth = SCREEN_WIDTH - 20;
    int barX = 10;
    
    // Clear marker area below bar - make it bigger for larger triangle
    M5.Display.fillRect(barX, barY + barHeight + 1, barWidth, 16, TFT_BLACK);
    
    // Don't draw marker if no heart rate or disconnected
    if (bpm <= 0 || !deviceConnected) {
        return;
    }
    
    // Calculate marker position
    int markerX;
    if (bpm >= 200) {
        markerX = barX + barWidth - 4;
    } else {
        markerX = barX + (bpm * barWidth / 200);
    }
    
    // Draw LARGER white marker triangle - more than double size
    // Triangle is now 18 pixels wide (was 8) and 12 pixels tall (was ~6)
    M5.Display.fillTriangle(markerX - 9, barY + barHeight + 12,  // Left point
                           markerX + 9, barY + barHeight + 12,     // Right point  
                           markerX, barY + barHeight + 1, TFT_WHITE); // Top point (raised slightly)
}

int getZone(int bpm) {
    if (bpm < 60) return 0;   // Rest
    if (bpm < 100) return 1;  // Normal
    if (bpm < 130) return 2;  // Fat Burn
    if (bpm < 160) return 3;  // Cardio
    return 4;                  // Peak
}

void updateHeartRateHistory() {
    if (millis() - lastHistoryUpdate > 1000) {
        heartRateHistory[historyIndex] = currentHeartRate;
        historyIndex = (historyIndex + 1) % GRAPH_POINTS;
        lastHistoryUpdate = millis();
    }
}

void drawDebugInfo() {
    if (lastDataReceived > 0 && deviceConnected) {
        dataGapSeconds = (millis() - lastDataReceived) / 1000;
    }
    
    bool hasProblems = deviceConnected && dataGapSeconds > 5;
    
    M5.Display.fillRect(0, 155, SCREEN_WIDTH, 25, TFT_BLACK);
    
    if (!hasProblems && !showDebugInfo) {
        return;
    }
    
    if (!deviceConnected && !showDebugInfo) {
        return;
    }
    
    M5.Display.setTextSize(1);
    M5.Display.setTextDatum(top_left);
    
    uint16_t statusColor;
    String statusText;
    
    if (!deviceConnected) {
        statusColor = TFT_DARKGREY;
        statusText = "DISCONNECTED";
    } else if (dataGapSeconds > 10) {
        statusColor = TFT_RED;
        statusText = "STALLED";
    } else if (dataGapSeconds > 5) {
        statusColor = TFT_YELLOW;
        statusText = "DELAYED";
    } else {
        statusColor = TFT_GREEN;
        statusText = "ACTIVE";
    }
    
    M5.Display.fillCircle(10, 165, 4, statusColor);
    M5.Display.setTextColor(statusColor);
    M5.Display.drawString(statusText, 20, 160);
    
    M5.Display.setTextColor(TFT_WHITE);
    String debugStr = "Gap:" + String(dataGapSeconds) + "s Pkts:" + String(packetsThisSession) + 
                     "/" + String(totalPacketsReceived) + " Max:" + String(maxGapSeconds) + "s";
    M5.Display.drawString(debugStr, 20, 170);
    
    if (deviceConnected && dataGapSeconds > 10) {
        M5.Display.setTextSize(1);
        M5.Display.setTextColor(TFT_RED);
        M5.Display.setTextDatum(top_center);
        M5.Display.drawString("CHECK WATCH WAKE!", 160, 170);
    }
}

void drawGraph() {
    bool hasProblems = (deviceConnected && dataGapSeconds > 5) || showDebugInfo;
    
    int graphX = 10;
    int graphY = hasProblems ? 180 : 155;
    int graphWidth = SCREEN_WIDTH - 20;
    int graphHeight = hasProblems ? 45 : 70;
    
    M5.Display.fillRect(graphX, 155, graphWidth, 70, TFT_BLACK);
    M5.Display.drawRect(graphX, graphY, graphWidth, graphHeight, TFT_DARKGREY);
    
    int minBPM = 200;
    int maxBPM = 40;
    int validPoints = 0;
    
    for (int i = 0; i < GRAPH_POINTS; i++) {
        if (heartRateHistory[i] > 0) {
            if (heartRateHistory[i] < minBPM) minBPM = heartRateHistory[i];
            if (heartRateHistory[i] > maxBPM) maxBPM = heartRateHistory[i];
            validPoints++;
        }
    }
    
    if (validPoints == 0) {
        minBPM = 60;
        maxBPM = 120;
    } else {
        int range = maxBPM - minBPM;
        int buffer = max(10, range / 10);
        minBPM = max(40, minBPM - buffer);
        maxBPM = min(200, maxBPM + buffer);
    }
    
    int range = maxBPM - minBPM;
    
    if (deviceConnected || validPoints > 0) {
        float pixelWidth = (float)graphWidth / GRAPH_POINTS;
        for (int i = 0; i < GRAPH_POINTS - 1; i++) {
            int idx1 = (historyIndex + i) % GRAPH_POINTS;
            int idx2 = (historyIndex + i + 1) % GRAPH_POINTS;
            
            if (heartRateHistory[idx1] > 0 && heartRateHistory[idx2] > 0) {
                int x1 = graphX + (i * pixelWidth);
                int x2 = graphX + ((i + 1) * pixelWidth);
                
                float y1_scaled = (float)(heartRateHistory[idx1] - minBPM) / range;
                float y2_scaled = (float)(heartRateHistory[idx2] - minBPM) / range;
                
                int y1 = graphY + graphHeight - (y1_scaled * graphHeight);
                int y2 = graphY + graphHeight - (y2_scaled * graphHeight);
                
                y1 = constrain(y1, graphY, graphY + graphHeight);
                y2 = constrain(y2, graphY, graphY + graphHeight);
                
                uint16_t lineColor = ZONE_COLORS[getZone(heartRateHistory[idx2])];
                M5.Display.drawLine(x1, y1, x2, y2, lineColor);
                
                if (abs(y2 - y1) < graphHeight/2) {
                    M5.Display.drawLine(x1, y1-1, x2, y2-1, lineColor);
                }
            }
        }
    }
    
    M5.Display.setTextSize(1);
    M5.Display.setTextColor(TFT_WHITE);
    M5.Display.setTextDatum(bottom_left);
    M5.Display.drawString("2 MIN", graphX + 2, graphY + graphHeight - 2);
    M5.Display.setTextDatum(bottom_right);
    M5.Display.drawString(String(minBPM) + "-" + String(maxBPM), graphX + graphWidth - 2, graphY + graphHeight - 2);
}

void updateDisplay() {
    currentZone = getZone(currentHeartRate);
    
    static int lastMarkerBPM = -1;
    if (lastMarkerBPM != currentHeartRate) {
        drawMarkerOnBar(currentHeartRate);
        lastMarkerBPM = currentHeartRate;
    }
    
    updateHeartRateHistory();
    drawDebugInfo();
    drawGraph();
    
    if (!deviceConnected) {
        if (!waitingScreenDrawn) {
            M5.Display.fillRect(0, 35, SCREEN_WIDTH, 120, TFT_BLACK);
            M5.Display.setTextDatum(middle_center);
            
            // Back to the v29 style that looked good
            M5.Display.setTextColor(TFT_WHITE);
            M5.Display.setTextSize(2);  // Back to size 2
            M5.Display.drawString("WAITING FOR", 160, 80);
            M5.Display.drawString("WEAR OS", 160, 110);
            
            M5.Display.setTextSize(1);
            M5.Display.setTextColor(TFT_DARKGREY);
            M5.Display.drawString("Advertising as: HRZone", 160, 140);
            
            waitingScreenDrawn = true;
            displayedHeartRate = -1;
        }
        lastConnected = deviceConnected;
        return;
    }
    
    waitingScreenDrawn = false;
    
    static bool firstConnect = true;
    if (deviceConnected && (firstConnect || lastConnected != deviceConnected)) {
        M5.Display.fillRect(0, 35, SCREEN_WIDTH, 120, TFT_BLACK);
        firstConnect = false;
    }
    
    static int lastDisplayedZone = -1;
    bool needsFullRedraw = (lastDisplayedZone != currentZone) || 
                          (displayedHeartRate <= 0 && currentHeartRate > 0) ||
                          (deviceConnected != lastConnected);
    
    if (displayedHeartRate == currentHeartRate && !needsFullRedraw) {
        return;
    }
    
    if (currentHeartRate > 0) {
        // Bigger rectangle that fills more screen
        int rectWidth = SCREEN_WIDTH - 20;
        int rectX = 10;
        int rectHeight = 120;  // Big box
        int rectY = 33;
        
        if (needsFullRedraw) {
            M5.Display.fillRect(0, 33, SCREEN_WIDTH, 122, TFT_BLACK);
            
            if (dataGapSeconds > 15) {
                M5.Display.drawRect(rectX - 1, rectY - 1, rectWidth + 2, rectHeight + 2, TFT_RED);
                M5.Display.drawRect(rectX, rectY, rectWidth, rectHeight, TFT_RED);
            }
            
            // Draw colored background
            M5.Display.fillRect(rectX + 2, rectY + 2, rectWidth - 4, rectHeight - 4, ZONE_COLORS[currentZone]);
            
            lastDisplayedZone = currentZone;
        } else {
            // Just clear the areas where text goes - adjusted for bigger text
            M5.Display.fillRect(rectX + 15, rectY + 15, 170, 90, ZONE_COLORS[currentZone]);  // Larger HR area for size 3
            M5.Display.fillRect(rectX + 185, rectY + 30, 110, 70, ZONE_COLORS[currentZone]); // Zone/BPM area
        }
        
        // Draw HR number - WORKING SMOOTH FONT SOLUTION
        String hrText = String(currentHeartRate);
        
        // M5GFX fonts that ARE included and WILL work
        M5.Display.setFont(&fonts::FreeSansBold24pt7b);  // 24pt bold - smooth
        M5.Display.setTextSize(2);  // Double it for size you want (no decimals!)
        M5.Display.setTextColor(TFT_BLACK);
        M5.Display.setTextDatum(middle_center);
        M5.Display.drawString(hrText, rectX + 75, rectY + 70);  // Moved down from 60 to 70
        
        // Draw Zone name - smooth font
        M5.Display.setFont(&fonts::FreeSansBold12pt7b);  // Bold for better visibility
        M5.Display.setTextSize(1);
        M5.Display.drawString(ZONE_NAMES[currentZone], rectX + 225, rectY + 45);
        
        // Draw "BPM"
        M5.Display.setFont(&fonts::FreeSans9pt7b);
        M5.Display.setTextSize(1);
        M5.Display.drawString("BPM", rectX + 225, rectY + 80);
        
        // Reset
        M5.Display.setFont(&fonts::Font0);
        M5.Display.setTextSize(1);
        
        // Reset datum
        M5.Display.setTextDatum(middle_center);
        
        // Show data age if stale
        if (dataGapSeconds > 15) {
            M5.Display.setTextFont(1);
            M5.Display.setTextSize(1);
            M5.Display.setTextColor(TFT_BLACK);
            M5.Display.drawString("(" + String(dataGapSeconds) + "s old)", 160, rectY + 108);
        }
        
        M5.Display.setTextFont(1);
    } else {
        if (displayedHeartRate != 0) {
            M5.Display.fillRect(0, 33, SCREEN_WIDTH, 122, TFT_BLACK);
            M5.Display.setTextFont(4);
            M5.Display.setTextSize(2);
            M5.Display.setTextColor(TFT_DARKGREY);
            M5.Display.setTextDatum(middle_center);
            M5.Display.drawString("---", 160, 90);
            M5.Display.setTextFont(1);
            lastDisplayedZone = -1;
        }
    }
    
    displayedHeartRate = currentHeartRate;
    lastConnected = deviceConnected;
}

void loop() {
    M5.update();
    
    if (M5.BtnA.wasPressed()) {
        Serial.println("Button A: Forcing BLE restart");
        waitingScreenDrawn = false;
        BLEDevice::startAdvertising();
    }
    
    if (M5.BtnB.wasPressed()) {
        showDebugInfo = !showDebugInfo;
        Serial.printf("Button B: Debug display %s\n", showDebugInfo ? "ON" : "OFF");
        Serial.printf("Connected: %s\n", deviceConnected ? "YES" : "NO");
        Serial.printf("Current HR: %d\n", currentHeartRate);
        Serial.printf("Packets: %d total, %d session\n", totalPacketsReceived, packetsThisSession);
        Serial.printf("Data gap: %d seconds\n", dataGapSeconds);
        Serial.printf("Max gap: %d seconds\n", maxGapSeconds);
        Serial.printf("Failures: %d\n", consecutiveFailures);
    }
    
    if (M5.BtnC.wasPressed()) {
        Serial.println("Button C: Reset statistics");
        totalPacketsReceived = 0;
        packetsThisSession = 0;
        maxGapSeconds = 0;
        consecutiveFailures = 0;
    }
    
    if (!deviceConnected) {
        unsigned long now = millis();
        if (now - lastAdvertiseTime > 1000) {
            pServer->startAdvertising();
            lastAdvertiseTime = now;
            
            static unsigned long lastDisconnectLog = 0;
            if (now - lastDisconnectLog > 10000) {
                Serial.println("Still advertising... waiting for connection");
                lastDisconnectLog = now;
            }
        }
    }
    
    if (deviceConnected && lastDataReceived > 0) {
        unsigned long timeSinceData = (millis() - lastDataReceived) / 1000;
        if (timeSinceData > 30) {
            Serial.printf("ERROR: No data for %lu seconds - connection may be dead\n", timeSinceData);
        }
    }
    
    static unsigned long lastUpdate = 0;
    if (millis() - lastUpdate > 500) {
        updateDisplay();
        lastUpdate = millis();
    }
    
    delay(50);
}