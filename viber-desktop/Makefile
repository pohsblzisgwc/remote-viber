CC ?= gcc
CFLAGS ?= -O2 -Wall -Wextra -Werror
GTK_FLAGS := $(shell pkg-config --cflags --libs gtk+-3.0 webkit2gtk-4.1)

WIN_CC ?= x86_64-w64-mingw32-gcc
WIN_FLAGS := -O2 -Wall -Wextra -Werror -mwindows -municode -lws2_32 -lshell32 -ldwmapi -luser32 -lgdi32

TARGET_LINUX := ../dist-bin/viber-desktop-linux
TARGET_WIN := ../dist-bin/RemoteViber.exe

all: $(TARGET_LINUX) $(TARGET_WIN)

linux: $(TARGET_LINUX)

windows: $(TARGET_WIN)

$(TARGET_LINUX): src/main.c
	@mkdir -p ../dist-bin
	$(CC) $(CFLAGS) $< -o $@ $(GTK_FLAGS)
	@echo "Native Linux desktop binary built successfully: $(TARGET_LINUX)"

$(TARGET_WIN): src/win_main.c
	@mkdir -p ../dist-bin
	$(WIN_CC) $< -o $@ $(WIN_FLAGS)
	@x86_64-w64-mingw32-strip -s $@ || true
	@echo "Native Windows desktop executable built successfully: $(TARGET_WIN)"

clean:
	rm -f $(TARGET_LINUX) $(TARGET_WIN)

.PHONY: all linux windows clean
