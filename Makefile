JAVAC := javac
JAVA := java
SRC_DIR := src
OUT_DIR := out
MAIN := server.Main
CONFIG := config.json

SOURCES := $(shell find $(SRC_DIR) -name '*.java')

.PHONY: all build run clean test

all: build

build:
	@mkdir -p $(OUT_DIR)
	$(JAVAC) -d $(OUT_DIR) $(SOURCES)
	@echo "Build complete -> $(OUT_DIR)"

run: build
	$(JAVA) -cp $(OUT_DIR) $(MAIN) $(CONFIG)

clean:
	rm -rf $(OUT_DIR)
