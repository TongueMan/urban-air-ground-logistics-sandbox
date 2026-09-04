package com.zhixun.demo;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BiConsumer;

@Component
public class MqttBridge {
    private final String brokerUrl;
    private final String clientId;
    private volatile MqttClient client;
    private volatile BiConsumer<String, String> listener = (topic, payload) -> {};

    public MqttBridge(@Value("${demo.mqtt-url}") String brokerUrl, @Value("${demo.mqtt-client-id}") String clientId) {
        this.brokerUrl = brokerUrl;
        this.clientId = clientId;
    }

    public void setListener(BiConsumer<String, String> listener) { this.listener = listener; }

    @PostConstruct
    void start() { connect(); }

    private synchronized void connect() {
        try {
            if (client != null && client.isConnected()) return;
            client = new MqttClient(brokerUrl, clientId + "-" + UUID.randomUUID().toString().substring(0, 8), new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(5);
            client.setCallback(new MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    try { client.subscribe("urban-air-ground/sessions/+/+/+/telemetry", 0); } catch (MqttException ignored) {}
                }
                @Override public void connectionLost(Throwable cause) {}
                @Override public void messageArrived(String topic, MqttMessage message) {
                    listener.accept(topic, new String(message.getPayload(), StandardCharsets.UTF_8));
                }
                @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            });
            client.connect(options);
            client.subscribe("urban-air-ground/sessions/+/+/+/telemetry", 0);
        } catch (MqttException exception) {
            throw new IllegalStateException("无法连接 MQTT: " + brokerUrl, exception);
        }
    }

    public void publish(String topic, String payload) {
        try {
            if (client == null || !client.isConnected()) connect();
            client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 0, false);
        } catch (MqttException exception) {
            throw new IllegalStateException("MQTT 发布失败", exception);
        }
    }

    @PreDestroy
    void stop() {
        try { if (client != null) client.disconnectForcibly(500, 500); } catch (MqttException ignored) {}
    }
}
