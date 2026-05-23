# Makefile for Carnet
# Run with BUILD=prod for release build, or BUILD=debug (default) for debug.

APP_ID := com.carnet.app/.MainActivity
PKG := com.carnet.app
GRADLEW := ./gradlew

# Device IDs
SAMSUNG_ID := 616ecbcf
PIXEL4A_ID := 0B201JECB13875
PIXEL9A_ID := 59101JEBF02652

# Build variant: debug (default) or prod (release)
BUILD ?= debug
ifeq ($(BUILD),prod)
  GRADLE_VARIANT := Release
else
  BUILD := debug
  GRADLE_VARIANT := Debug
endif

.PHONY: all
all: help

# === Build ===

.PHONY: assemble
assemble:
	$(GRADLEW) assemble$(GRADLE_VARIANT)

.PHONY: install
install:
	@if adb devices | grep -q 'device$$'; then \
		$(GRADLEW) install$(GRADLE_VARIANT); \
	else \
		echo "No device connected. Building APK only."; \
		$(GRADLEW) assemble$(GRADLE_VARIANT); \
	fi

.PHONY: check
check:
	$(GRADLEW) check

.PHONY: lint
lint:
	$(GRADLEW) lint$(GRADLE_VARIANT)

.PHONY: test
test:
	$(GRADLEW) test$(GRADLE_VARIANT)UnitTest

.PHONY: clean
clean:
	$(GRADLEW) clean

# === Device ===

.PHONY: devices
devices:
	adb devices

.PHONY: run
run: install
	@adb shell am start -n $(APP_ID)

.PHONY: run-pixel4a
run-pixel4a: install
	adb -s $(PIXEL4A_ID) shell am start -n $(APP_ID)

.PHONY: run-pixel9a
run-pixel9a: install
	adb -s $(PIXEL9A_ID) shell am start -n $(APP_ID)

.PHONY: run-samsung
run-samsung: install
	adb -s $(SAMSUNG_ID) shell am start -n $(APP_ID)

.PHONY: logs
logs:
	@trap '$(GRADLEW) --stop' EXIT INT TERM; adb logcat --pid=$$(adb shell pidof $(PKG))

.PHONY: clear-data
clear-data:
	adb shell pm clear $(PKG)

# === Maintenance ===

.PHONY: clean-daemons
clean-daemons:
	$(GRADLEW) --stop

# === Help ===

.PHONY: help
help:
	@echo "Carnet"
	@echo ""
	@echo "Build:"
	@echo "  make assemble       - Build APK (no device needed). BUILD=prod for release."
	@echo "  make install        - Build + install on connected device. BUILD=prod for release."
	@echo "  make check          - Run all Gradle checks (lint + tests)."
	@echo "  make lint           - Run Android lint."
	@echo "  make test           - Run unit tests."
	@echo "  make clean          - Clean build outputs."
	@echo ""
	@echo "Device:"
	@echo "  make run            - Install and launch on connected device."
	@echo "  make run-pixel4a    - Install and run on Pixel 4a (ID: $(PIXEL4A_ID))."
	@echo "  make run-pixel9a    - Install and run on Pixel 9a (ID: $(PIXEL9A_ID))."
	@echo "  make run-samsung    - Install and run on Samsung (ID: $(SAMSUNG_ID))."
	@echo "  make devices        - List connected ADB devices."
	@echo "  make logs           - Tail app logs (Ctrl+C to stop)."
	@echo "  make clear-data     - Wipe app data on device."
	@echo ""
	@echo "Maintenance:"
	@echo "  make clean-daemons  - Stop idle Gradle daemons (frees ~4GB each)."
	@echo ""
	@echo "Build variant: BUILD=debug (default) or BUILD=prod"
