package com.Tigrik121123.telegrambridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
// Импорты SignedMessage и MessageType уже были, но на всякий случай:
import net.minecraft.network.message.SignedMessage;
import net.minecraft.network.message.MessageType;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

public class TelegramBridgeMod implements ModInitializer {
    public static final String MOD_ID = "telegram_bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static String telegramBotToken = null;
    private static String telegramChatId = null;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .build();

    @Override
    public void onInitialize() {
        LOGGER.info("[TelegramBridge] Mod Initializing!");
        registerCommands();
        registerMessageListener();
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("token")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("token_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        telegramBotToken = StringArgumentType.getString(context, "token_value");
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Bot Token установлен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Bot Token set to: '{}'", telegramBotToken); // Логируем сам токен (осторожно с этим в публичных логах)
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("chatid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("chat_id_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        telegramChatId = StringArgumentType.getString(context, "chat_id_value");
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Chat ID установлен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Chat ID set to: '{}'", telegramChatId);
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("say")
                .requires(source -> source.hasPermissionLevel(0))
                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                    .executes(context -> {
                        String messageText = StringArgumentType.getString(context, "text");
                        String senderName = "Server";
                        if (context.getSource().isExecutedByPlayer()) {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player != null) {
                                senderName = player.getGameProfile().getName();
                            }
                        }
                        LOGGER.info("[TelegramBridge] /say command executed by '{}'. Message: '{}'", senderName, messageText);
                        String telegramMessage = "[" + senderName + " via /say]: " + messageText;
                        sendToTelegram(telegramMessage, false, senderName);
                        context.getSource().sendFeedback(() -> Text.literal("Попытка отправить сообщение в Telegram... (см. логи сервера)"), false);
                        return 1;
                    })));
        });
    }

    private void registerMessageListener() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, typeKey) -> {
            // Логируем все сообщения для отладки
            LOGGER.info("[TelegramBridge] CHAT_MESSAGE event. Sender: {}, TypeKey: {}, Content: '{}'",
                    (sender != null ? sender.getGameProfile().getName() : "NULL_SENDER"),
                    typeKey.type().chat().translationKey(), // Получаем ключ типа сообщения
                    message.getContent().getString());

            if (sender == null) {
                String rawMessage = message.getContent().getString();
                String messageOrigin = typeKey.type().chat().translationKey();

                if (rawMessage.contains(" via /say]:")) {
                     LOGGER.trace("[TelegramBridge] Ignoring self-generated /say message to prevent loop: {}", rawMessage);
                     return;
                }

                LOGGER.info("[TelegramBridge] System message detected. Origin: '{}', Content: '{}'", messageOrigin, rawMessage);
                sendToTelegram(rawMessage, true, messageOrigin);
            }
        });
    }

    private void sendToTelegram(String message, boolean addPrefix, String origin) {
        LOGGER.info("[TelegramBridge] sendToTelegram called. Message: '{}', addPrefix: {}, origin: '{}'", message, addPrefix, origin);
        if (telegramBotToken == null || telegramChatId == null || telegramBotToken.isEmpty() || telegramChatId.isEmpty()) {
            LOGGER.warn("[TelegramBridge] Telegram Bot Token или Chat ID не установлены или пусты. Сообщение НЕ отправлено: {}", message);
            return;
        }

        String finalMessage;
        if (addPrefix) {
            String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String senderDisplay = (origin != null && !origin.isEmpty()) ? origin : "System";
            finalMessage = String.format("[%s] [%s]: %s", dateTime, senderDisplay, message);
        } else {
            finalMessage = message;
        }

        LOGGER.info("[TelegramBridge] Attempting to send to Telegram. ChatID: {}, Final Message: '{}'", telegramChatId, finalMessage);

        CompletableFuture.runAsync(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage";
                String requestBody = "chat_id=" + URLEncoder.encode(telegramChatId, StandardCharsets.UTF_8) +
                                     "&text=" + URLEncoder.encode(finalMessage, StandardCharsets.UTF_8) +
                                     "&parse_mode=HTML";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlString))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                LOGGER.debug("[TelegramBridge] Sending HTTP request to: {} with body: {}", urlString, requestBody);

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    LOGGER.info("[TelegramBridge] Сообщение успешно отправлено в Telegram. Response Code: {}. Original message: '{}'", response.statusCode(), finalMessage);
                } else {
                    LOGGER.error("[TelegramBridge] Telegram API Error. Status: {}, Body: {}. Original message: '{}'", response.statusCode(), response.body(), finalMessage);
                }
            } catch (Exception e) {
                // Оборачиваем finalMessage в одинарные кавычки для лога, на случай если он содержит символы форматирования SLF4J
                LOGGER.error("[TelegramBridge] Exception during Telegram send for message '{}':", finalMessage, e);
            }
        });
    }
}
