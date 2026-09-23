CC ?= cc
CFLAGS ?= -O2 -Wall -Wextra -Wpedantic -std=c11
LDLIBS = -lmosquitto -lssl -lcrypto -lopus -lasound -lpthread -lm

.PHONY: all clean
all: mqtt-call-listener

mqtt-call-listener: mqtt-call-listener.c
	$(CC) $(CFLAGS) -o $@ $< $(LDLIBS)

clean:
	rm -f mqtt-call-listener
