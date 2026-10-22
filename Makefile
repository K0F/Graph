ANDROID_SDK ?= $(HOME)/Android/Sdk
BUILD_TOOLS ?= $(ANDROID_SDK)/build-tools/34.0.0
PLATFORM    ?= $(ANDROID_SDK)/platforms/android-34
NDK         ?= $(ANDROID_SDK)/ndk/27.3.13750724
NDK_CLANG   ?= $(NDK)/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang

AAPT2       := $(BUILD_TOOLS)/aapt2
D8          := $(BUILD_TOOLS)/d8
ZIPALIGN    := $(BUILD_TOOLS)/zipalign
APKSIGNER   := $(BUILD_TOOLS)/apksigner
ANDROID_JAR := $(PLATFORM)/android.jar
KEYSTORE    ?= $(HOME)/.android/debug.keystore

BUILD_DIR   := build
CLASSES_DIR := $(BUILD_DIR)/classes
STAGE_DIR   := $(BUILD_DIR)/stage
APK_UNALIGN := $(BUILD_DIR)/Graph-unaligned.apk
APK_UNSIGNED:= $(BUILD_DIR)/Graph-unsigned.apk
APK         := $(BUILD_DIR)/Graph.apk

JAVA_SRC := src/cz/kof/graph/GraphFileProvider.java \
            src/cz/kof/graph/VideoRenderer.java \
            src/cz/kof/graph/MainActivity.java

all: apk

assets/noise.png:
	@mkdir -p assets
	@if [ -f ../gram/dada/noise.png ]; then \
		cp -f ../gram/dada/noise.png assets/noise.png; \
	elif [ -f "$(HOME)/.local/share/gram/noise.png" ]; then \
		cp -f "$(HOME)/.local/share/gram/noise.png" assets/noise.png; \
	fi

assets/gomotor.ttf:
	@mkdir -p assets
	@if [ -f ../gram/genmontage/gomotor.ttf ]; then \
		cp -f ../gram/genmontage/gomotor.ttf assets/gomotor.ttf; \
	elif [ -f "$(HOME)/.local/share/gram/gomotor.ttf" ]; then \
		cp -f "$(HOME)/.local/share/gram/gomotor.ttf" assets/gomotor.ttf; \
	fi

$(STAGE_DIR)/lib/arm64-v8a/libgraph_dada.so: jni/graph_dada.c
	@mkdir -p $(STAGE_DIR)/lib/arm64-v8a
	$(NDK_CLANG) -shared -fPIC -O2 -Wall -Wextra -o $@ $<

apk: assets/noise.png assets/gomotor.ttf $(STAGE_DIR)/lib/arm64-v8a/libgraph_dada.so $(JAVA_SRC) AndroidManifest.xml
	@mkdir -p $(CLASSES_DIR) $(BUILD_DIR)/gen
	$(AAPT2) link -o $(APK_UNALIGN) \
		-I $(ANDROID_JAR) \
		--manifest AndroidManifest.xml \
		-A assets \
		--java $(BUILD_DIR)/gen
	javac --release 11 -classpath $(ANDROID_JAR) -d $(CLASSES_DIR) $(JAVA_SRC) $(BUILD_DIR)/gen/cz/kof/graph/R.java
	$(D8) --lib $(ANDROID_JAR) --min-api 26 --output $(STAGE_DIR) $(CLASSES_DIR)/cz/kof/graph/*.class
	cd $(STAGE_DIR) && zip -q -r $(abspath $(APK_UNALIGN)) classes.dex lib/arm64-v8a/libgraph_dada.so
	$(ZIPALIGN) -p -f 4 $(APK_UNALIGN) $(APK_UNSIGNED)
	@if [ ! -f "$(KEYSTORE)" ]; then \
		mkdir -p "$(dir $(KEYSTORE))"; \
		keytool -genkeypair -v -keystore "$(KEYSTORE)" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"; \
	fi
	$(APKSIGNER) sign --ks "$(KEYSTORE)" --ks-pass pass:android --key-pass pass:android --out $(APK) $(APK_UNSIGNED)
	@if [ -d "$(HOME)/phone-DCIM" ]; then \
		mkdir -p "$(HOME)/phone-DCIM/Graph"; \
		cp -f $(APK) "$(HOME)/phone-DCIM/Graph/Graph.apk"; \
		echo "[Graph] Synced APK to $(HOME)/phone-DCIM/Graph/Graph.apk (Syncthing)"; \
	fi
	@echo "[Graph] Built signed APK: $(APK)"

install: apk
	@if adb get-state >/dev/null 2>&1; then \
		adb install -r $(APK) || adb push $(APK) /sdcard/Download/Graph.apk; \
		adb shell appops set cz.kof.graph MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true; \
		adb shell pm grant cz.kof.graph android.permission.READ_MEDIA_IMAGES 2>/dev/null || true; \
		adb shell pm grant cz.kof.graph android.permission.READ_MEDIA_VIDEO 2>/dev/null || true; \
		adb shell pm grant cz.kof.graph android.permission.READ_MEDIA_AUDIO 2>/dev/null || true; \
		adb shell pm grant cz.kof.graph android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true; \
		adb shell pm grant cz.kof.graph android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true; \
	else \
		echo "[Graph] No USB adb device attached — Graph.apk is synced via Syncthing in DCIM/Graph/Graph.apk"; \
	fi

run: install
	adb shell am start -n cz.kof.graph/.MainActivity

clean:
	rm -rf $(BUILD_DIR)

.PHONY: all apk install run clean
