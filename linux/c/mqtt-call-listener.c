#define _DEFAULT_SOURCE
#include <alsa/asoundlib.h>
#include <errno.h>
#include <mosquitto.h>
#include <openssl/evp.h>
#include <opus/opus.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#define HEADER_SIZE 34
#define TAG_SIZE 16
#define NONCE_SIZE 12
#define SESSION_SIZE 16
#define MAX_FRAMES 10
#define FRAME_SAMPLES 320
#define SAMPLE_RATE 16000
#define STARTUP_BATCHES 3
#define MAX_BATCHES 10

typedef struct {
    char broker[256], username[128], password[128], channel[32], key[256], audio_device[128];
    int port, tls;
} config_t;

typedef struct batch {
    uint8_t session[SESSION_SIZE];
    uint32_t sequence;
    size_t frame_count, frame_sizes[MAX_FRAMES];
    uint8_t *frames[MAX_FRAMES];
    struct batch *next;
} batch_t;

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t changed;
    batch_t *head, *tail;
    size_t count;
    int playing, released, have_session, have_sequence, have_claim_sequence, stopping;
    uint8_t session[SESSION_SIZE];
    uint32_t last_sequence;
    uint32_t last_claim_sequence;
    char audio_device[128];
} queue_t;

typedef struct {
    uint8_t key[32];
    char topic[96];
    queue_t *queue;
    volatile sig_atomic_t connected;
} mqtt_context_t;

static volatile sig_atomic_t stopping;

static void stop_signal(int signal_number) {
    (void)signal_number;
    stopping = 1;
}

static void trim(char *value) {
    char *start = value;
    size_t length;
    while (*start == ' ' || *start == '\t' || *start == '\r' || *start == '\n') start++;
    if (start != value) memmove(value, start, strlen(start) + 1);
    length = strlen(value);
    while (length && (value[length - 1] == ' ' || value[length - 1] == '\t' ||
                      value[length - 1] == '\r' || value[length - 1] == '\n')) value[--length] = 0;
}

static int load_config(const char *path, config_t *config) {
    FILE *file = fopen(path, "r");
    char line[512];
    if (!file) {
        fprintf(stderr, "cannot open %s: %s\n", path, strerror(errno));
        return -1;
    }
    memset(config, 0, sizeof(*config));
    snprintf(config->broker, sizeof(config->broker), "broker.emqx.io");
    config->port = 1883;
    snprintf(config->channel, sizeof(config->channel), "3344");
    snprintf(config->key, sizeof(config->key), "PTT-DEMO-3344");
    snprintf(config->audio_device, sizeof(config->audio_device), "default");
    while (fgets(line, sizeof(line), file)) {
        char *equals;
        trim(line);
        if (!line[0] || line[0] == '#') continue;
        equals = strchr(line, '=');
        if (!equals) continue;
        *equals++ = 0;
        trim(line);
        trim(equals);
        if (!strcmp(line, "broker")) snprintf(config->broker, sizeof(config->broker), "%s", equals);
        else if (!strcmp(line, "port")) config->port = atoi(equals);
        else if (!strcmp(line, "tls")) config->tls = !strcasecmp(equals, "true") || !strcmp(equals, "1");
        else if (!strcmp(line, "username")) snprintf(config->username, sizeof(config->username), "%s", equals);
        else if (!strcmp(line, "password")) snprintf(config->password, sizeof(config->password), "%s", equals);
        else if (!strcmp(line, "channel")) snprintf(config->channel, sizeof(config->channel), "%s", equals);
        else if (!strcmp(line, "key")) snprintf(config->key, sizeof(config->key), "%s", equals);
        else if (!strcmp(line, "audio_device")) snprintf(config->audio_device, sizeof(config->audio_device), "%s", equals);
    }
    fclose(file);
    if (!config->broker[0] || config->port < 1 || config->port > 65535 || !config->channel[0] || !config->key[0] ||
        strlen(config->channel) > 12) {
        fprintf(stderr, "invalid broker, port, channel, or key in %s\n", path);
        return -1;
    }
    for (size_t index = 0; config->channel[index]; index++) {
        if (config->channel[index] < '0' || config->channel[index] > '9') {
            fprintf(stderr, "channel must contain only digits\n");
            return -1;
        }
    }
    return 0;
}

static int derive_key(const config_t *config, uint8_t output[32]) {
    char salt[128];
    int salt_length = snprintf(salt, sizeof(salt), "mqtt-ptt-v1/%s", config->channel);
    if (salt_length < 0 || (size_t)salt_length >= sizeof(salt)) return -1;
    return PKCS5_PBKDF2_HMAC(config->key, (int)strlen(config->key), (const unsigned char *)salt,
                             salt_length, 100000, EVP_sha256(), 32, output) == 1 ? 0 : -1;
}

static int decrypt_packet(const uint8_t *packet, size_t packet_size, const uint8_t key[32],
                          uint8_t *kind, uint8_t session[SESSION_SIZE], uint32_t *sequence,
                          uint8_t **plain, int *plain_size) {
    EVP_CIPHER_CTX *cipher;
    const uint8_t *nonce, *encrypted, *tag;
    size_t encrypted_size;
    int length, final_length, success = 0;
    if (packet_size < HEADER_SIZE + TAG_SIZE || packet[0] != 1 || packet[1] < 1 || packet[1] > 3) return 0;
    *kind = packet[1];
    memcpy(session, packet + 2, SESSION_SIZE);
    *sequence = ((uint32_t)packet[18] << 24) | ((uint32_t)packet[19] << 16) |
                ((uint32_t)packet[20] << 8) | packet[21];
    nonce = packet + 22;
    encrypted_size = packet_size - HEADER_SIZE - TAG_SIZE;
    encrypted = packet + HEADER_SIZE;
    tag = packet + packet_size - TAG_SIZE;
    *plain = malloc(encrypted_size ? encrypted_size : 1);
    cipher = EVP_CIPHER_CTX_new();
    if (!*plain || !cipher) goto done;
    if (EVP_DecryptInit_ex(cipher, EVP_aes_256_gcm(), NULL, NULL, NULL) != 1) goto done;
    if (EVP_CIPHER_CTX_ctrl(cipher, EVP_CTRL_GCM_SET_IVLEN, NONCE_SIZE, NULL) != 1) goto done;
    if (EVP_DecryptInit_ex(cipher, NULL, NULL, key, nonce) != 1) goto done;
    if (EVP_DecryptUpdate(cipher, NULL, &length, packet, HEADER_SIZE) != 1) goto done;
    if (EVP_DecryptUpdate(cipher, *plain, &length, encrypted, (int)encrypted_size) != 1) goto done;
    if (EVP_CIPHER_CTX_ctrl(cipher, EVP_CTRL_GCM_SET_TAG, TAG_SIZE, (void *)tag) != 1) goto done;
    if (EVP_DecryptFinal_ex(cipher, *plain + length, &final_length) != 1) goto done;
    *plain_size = length + final_length;
    success = 1;
done:
    EVP_CIPHER_CTX_free(cipher);
    if (!success) {
        free(*plain);
        *plain = NULL;
    }
    return success;
}

static void free_batch(batch_t *batch) {
    if (!batch) return;
    for (size_t index = 0; index < batch->frame_count; index++) free(batch->frames[index]);
    free(batch);
}

static void clear_queue_locked(queue_t *queue) {
    batch_t *batch = queue->head;
    while (batch) {
        batch_t *next = batch->next;
        free_batch(batch);
        batch = next;
    }
    queue->head = queue->tail = NULL;
    queue->count = 0;
}

static void reset_session_locked(queue_t *queue) {
    clear_queue_locked(queue);
    queue->playing = queue->released = queue->have_session = queue->have_sequence = queue->have_claim_sequence = 0;
}

static batch_t *decode_audio(const uint8_t session[SESSION_SIZE], uint32_t sequence,
                             const uint8_t *data, size_t data_size) {
    batch_t *batch;
    size_t position = 1;
    if (!data_size || data[0] < 1 || data[0] > MAX_FRAMES) return NULL;
    batch = calloc(1, sizeof(*batch));
    if (!batch) return NULL;
    memcpy(batch->session, session, SESSION_SIZE);
    batch->sequence = sequence;
    batch->frame_count = data[0];
    for (size_t index = 0; index < batch->frame_count; index++) {
        size_t frame_size;
        if (position + 2 > data_size) goto bad;
        frame_size = ((size_t)data[position] << 8) | data[position + 1];
        position += 2;
        if (!frame_size || position + frame_size > data_size) goto bad;
        batch->frames[index] = malloc(frame_size);
        if (!batch->frames[index]) goto bad;
        memcpy(batch->frames[index], data + position, frame_size);
        batch->frame_sizes[index] = frame_size;
        position += frame_size;
    }
    if (position != data_size) goto bad;
    return batch;
bad:
    free_batch(batch);
    return NULL;
}

static void enqueue(queue_t *queue, batch_t *batch) {
    pthread_mutex_lock(&queue->mutex);
    if (queue->have_session && memcmp(queue->session, batch->session, SESSION_SIZE)) {
        reset_session_locked(queue);
    }
    if (!queue->have_session) {
        memcpy(queue->session, batch->session, SESSION_SIZE);
        queue->have_session = 1;
        queue->have_sequence = 0;
    }
    if (queue->have_sequence && batch->sequence <= queue->last_sequence) {
        pthread_mutex_unlock(&queue->mutex);
        free_batch(batch);
        return;
    }
    queue->last_sequence = batch->sequence;
    queue->have_sequence = 1;
    if (queue->count == MAX_BATCHES) {
        batch_t *old = queue->head;
        queue->head = old->next;
        queue->count--;
        free_batch(old);
    }
    if (queue->tail) queue->tail->next = batch;
    else queue->head = batch;
    queue->tail = batch;
    queue->count++;
    if (queue->count >= STARTUP_BATCHES) queue->playing = 1;
    pthread_cond_signal(&queue->changed);
    pthread_mutex_unlock(&queue->mutex);
}

static void mark_claim(queue_t *queue, const uint8_t session[SESSION_SIZE], uint32_t sequence) {
    pthread_mutex_lock(&queue->mutex);
    if (queue->have_session && memcmp(queue->session, session, SESSION_SIZE)) reset_session_locked(queue);
    if (!queue->have_session) {
        memcpy(queue->session, session, SESSION_SIZE);
        queue->have_session = 1;
    }
    if (queue->have_claim_sequence && sequence <= queue->last_claim_sequence) {
        pthread_mutex_unlock(&queue->mutex);
        return;
    }
    if (queue->have_claim_sequence) {
        /* A new claim starts a new talk turn. Drop stale audio from the prior turn. */
        clear_queue_locked(queue);
        queue->playing = 0;
        queue->released = 0;
        queue->have_sequence = 0;
    }
    queue->last_claim_sequence = sequence;
    queue->have_claim_sequence = 1;
    queue->released = 0;
    pthread_cond_signal(&queue->changed);
    pthread_mutex_unlock(&queue->mutex);
}

static void mark_release(queue_t *queue, const uint8_t session[SESSION_SIZE]) {
    pthread_mutex_lock(&queue->mutex);
    if (queue->have_session && !memcmp(queue->session, session, SESSION_SIZE)) {
        queue->released = 1;
        if (queue->count) queue->playing = 1;
        else reset_session_locked(queue);
        pthread_cond_signal(&queue->changed);
    }
    pthread_mutex_unlock(&queue->mutex);
}

static int open_audio(const char *device, snd_pcm_t **pcm) {
    int result = snd_pcm_open(pcm, device, SND_PCM_STREAM_PLAYBACK, 0);
    if (result < 0) return result;
    result = snd_pcm_set_params(*pcm, SND_PCM_FORMAT_S16_LE, SND_PCM_ACCESS_RW_INTERLEAVED,
                                1, SAMPLE_RATE, 1, 100000);
    if (result < 0) {
        snd_pcm_close(*pcm);
        *pcm = NULL;
    }
    return result;
}

static void close_audio(snd_pcm_t **pcm) {
    if (*pcm) {
        snd_pcm_drain(*pcm);
        snd_pcm_close(*pcm);
        *pcm = NULL;
    }
}

static int write_samples(snd_pcm_t *pcm, const int16_t *samples, size_t count) {
    size_t offset = 0;
    while (offset < count && !stopping) {
        snd_pcm_sframes_t written = snd_pcm_writei(pcm, samples + offset, count - offset);
        if (written < 0) {
            written = snd_pcm_recover(pcm, (int)written, 1);
            if (written < 0) return -1;
        } else {
            offset += (size_t)written;
        }
    }
    return 0;
}

static void *playback_thread(void *argument) {
    queue_t *queue = argument;
    int opus_error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(SAMPLE_RATE, 1, &opus_error);
    snd_pcm_t *pcm = NULL;
    int16_t samples[FRAME_SAMPLES];
    if (!decoder || opus_error != OPUS_OK) {
        fprintf(stderr, "cannot initialize Opus decoder (%d)\n", opus_error);
        return NULL;
    }
    while (!stopping) {
        pthread_mutex_lock(&queue->mutex);
        while (!stopping && !queue->stopping && (!queue->playing || !queue->head))
            pthread_cond_wait(&queue->changed, &queue->mutex);
        if (stopping || queue->stopping) {
            pthread_mutex_unlock(&queue->mutex);
            break;
        }
        batch_t *batch = queue->head;
        queue->head = batch->next;
        if (!queue->head) queue->tail = NULL;
        queue->count--;
        pthread_mutex_unlock(&queue->mutex);

        if (!pcm && open_audio(queue->audio_device, &pcm) < 0) {
            fprintf(stderr, "cannot open audio device %s; dropping batch\n", queue->audio_device);
            free_batch(batch);
            continue;
        }
        for (size_t index = 0; index < batch->frame_count && !stopping; index++) {
            int decoded = opus_decode(decoder, batch->frames[index], (opus_int32)batch->frame_sizes[index],
                                      samples, FRAME_SAMPLES, 0);
            if (decoded < 0 || write_samples(pcm, samples, (size_t)decoded) < 0) {
                fprintf(stderr, "audio playback failed; releasing device\n");
                close_audio(&pcm);
                break;
            }
        }
        free_batch(batch);
        pthread_mutex_lock(&queue->mutex);
        if (!queue->count) {
            queue->playing = 0;
            if (queue->released) reset_session_locked(queue);
        }
        int queue_empty = queue->count == 0;
        pthread_mutex_unlock(&queue->mutex);
        /* Do not hold the speaker between packets. An underrun releases it now. */
        if (queue_empty) close_audio(&pcm);
    }
    close_audio(&pcm);
    opus_decoder_destroy(decoder);
    return NULL;
}

static void on_connect(struct mosquitto *client, void *userdata, int result) {
    mqtt_context_t *context = userdata;
    if (result != MOSQ_ERR_SUCCESS) {
        fprintf(stderr, "MQTT connection failed: %s\n", mosquitto_strerror(result));
        return;
    }
    context->connected = 1;
    result = mosquitto_subscribe(client, NULL, context->topic, 0);
    if (result == MOSQ_ERR_SUCCESS) fprintf(stderr, "listening on %s\n", context->topic);
    else fprintf(stderr, "MQTT subscribe failed: %s\n", mosquitto_strerror(result));
}

static void on_disconnect(struct mosquitto *client, void *userdata, int result) {
    mqtt_context_t *context = userdata;
    (void)client;
    context->connected = 0;
    if (!stopping && result != MOSQ_ERR_SUCCESS)
        fprintf(stderr, "MQTT disconnected: %s; Mosquitto will retry with backoff\n", mosquitto_strerror(result));
}

static void on_message(struct mosquitto *client, void *userdata, const struct mosquitto_message *message) {
    mqtt_context_t *context = userdata;
    uint8_t kind, session[SESSION_SIZE], *plain = NULL;
    uint32_t sequence;
    int plain_size;
    (void)client;
    if (!message || !message->payload || message->payloadlen < HEADER_SIZE + TAG_SIZE) return;
    if (!decrypt_packet(message->payload, (size_t)message->payloadlen, context->key, &kind,
                        session, &sequence, &plain, &plain_size)) return;
    if (kind == 1) mark_claim(context->queue, session, sequence);
    else if (kind == 2) mark_release(context->queue, session);
    else if (kind == 3) {
        batch_t *batch = decode_audio(session, sequence, plain, (size_t)plain_size);
        if (batch) enqueue(context->queue, batch);
    }
    free(plain);
}

static void on_log(struct mosquitto *client, void *userdata, int level, const char *message) {
    (void)client;
    (void)userdata;
    if (level == MOSQ_LOG_ERR) fprintf(stderr, "MQTT: %s\n", message);
}

int main(int argc, char **argv) {
    const char *config_path = argc == 2 ? argv[1] : "linux/listener.conf";
    config_t config;
    queue_t queue = {0};
    mqtt_context_t context;
    struct mosquitto *client;
    pthread_t playback;
    uint8_t key[32];
    int result, exit_code = EXIT_SUCCESS;
    if (argc > 2) {
        fprintf(stderr, "usage: %s [config-file]\n", argv[0]);
        return EXIT_FAILURE;
    }
    if (load_config(config_path, &config) < 0 || derive_key(&config, key) < 0) return EXIT_FAILURE;
    memset(&context, 0, sizeof(context));
    memcpy(context.key, key, sizeof(key));
    snprintf(context.topic, sizeof(context.topic), "call/channel/%s", config.channel);
    context.queue = &queue;
    memcpy(queue.audio_device, config.audio_device, sizeof(queue.audio_device));
    pthread_mutex_init(&queue.mutex, NULL);
    pthread_cond_init(&queue.changed, NULL);
    signal(SIGINT, stop_signal);
    signal(SIGTERM, stop_signal);
    if (pthread_create(&playback, NULL, playback_thread, &queue) != 0) {
        fprintf(stderr, "cannot start playback thread\n");
        return EXIT_FAILURE;
    }
    mosquitto_lib_init();
    client = mosquitto_new("mqtt-call-linux-listener", true, &context);
    if (!client) {
        fprintf(stderr, "cannot create MQTT client\n");
        stopping = 1;
        pthread_mutex_lock(&queue.mutex); queue.stopping = 1; pthread_cond_signal(&queue.changed); pthread_mutex_unlock(&queue.mutex);
        pthread_join(playback, NULL);
        mosquitto_lib_cleanup();
        return EXIT_FAILURE;
    }
    mosquitto_connect_callback_set(client, on_connect);
    mosquitto_disconnect_callback_set(client, on_disconnect);
    mosquitto_message_callback_set(client, on_message);
    mosquitto_log_callback_set(client, on_log);
    mosquitto_reconnect_delay_set(client, 2, 30, true);
    if (config.username[0]) mosquitto_username_pw_set(client, config.username, config.password);
    if (config.tls && mosquitto_tls_set(client, NULL, NULL, NULL, NULL, NULL) != MOSQ_ERR_SUCCESS) {
        fprintf(stderr, "cannot configure TLS\n");
        mosquitto_destroy(client); mosquitto_lib_cleanup(); return EXIT_FAILURE;
    }
    result = mosquitto_connect(client, config.broker, config.port, 30);
    if (result != MOSQ_ERR_SUCCESS) {
        fprintf(stderr, "MQTT connect failed: %s; Mosquitto will retry with backoff\n", mosquitto_strerror(result));
    } else {
        fprintf(stderr, "MQTT Call listener started for %s\n", context.topic);
    }
    result = mosquitto_loop_start(client);
    if (result != MOSQ_ERR_SUCCESS) {
        fprintf(stderr, "MQTT loop failed: %s\n", mosquitto_strerror(result));
        exit_code = EXIT_FAILURE;
    } else {
        while (!stopping) sleep(1);
        mosquitto_loop_stop(client, true);
    }
    if (result != MOSQ_ERR_SUCCESS && !stopping) exit_code = EXIT_FAILURE;
    stopping = 1;
    mosquitto_disconnect(client);
    mosquitto_destroy(client);
    mosquitto_lib_cleanup();
    pthread_mutex_lock(&queue.mutex);
    queue.stopping = 1;
    pthread_cond_signal(&queue.changed);
    pthread_mutex_unlock(&queue.mutex);
    pthread_join(playback, NULL);
    pthread_mutex_lock(&queue.mutex);
    clear_queue_locked(&queue);
    pthread_mutex_unlock(&queue.mutex);
    pthread_cond_destroy(&queue.changed);
    pthread_mutex_destroy(&queue.mutex);
    return exit_code;
}
