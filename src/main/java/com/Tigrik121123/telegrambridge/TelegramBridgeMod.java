package com.Tigrik121123.telegrambridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.message.SignedMessage; // Если еще не было
import net.minecraft.network.message.MessageType;   // Если еще не было
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
    public static final String MOD_ID = "telegram_bridge"; // Должен совпадать с id в fabric.mod.json
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static String telegramBotToken = null;
    private static String telegramChatId = null;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .build();

    @Override
    public void onInitialize() {
        LOGGER.info("Telegram Bridge Mod Initializing!");

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
                        LOGGER.info("Telegram Bot Token set via command.");
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("chatid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("chat_id_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        telegramChatId = StringArgumentType.getString(context, "chat_id_value");
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Chat ID установлен."), false);
                        LOGGER.info("Telegram Chat ID set via command.");
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("say")
                .requires(source -> source.hasPermissionLevel(0)) // Разрешим всем по умолчанию, можно изменить на 2 для админов
                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                    .executes(context -> {
                        String messageText = StringArgumentType.getString(context, "text");
                        String senderName = "Server"; // По умолчанию
                        if (context.getSource().isExecutedByPlayer()) {
                            ServerPlayerEntity player = context.getSource().getPlayer();
                            if (player != null) {
                                senderName = player.getGameProfile().getName();
                            }
                        }
                        
                        String telegramMessage = "[" + senderName + " via /say]: " + messageText;
                        sendToTelegram(telegramMessage, false, senderName);
                        context.getSource().sendFeedback(() -> Text.literal("Сообщение отправлено в Telegram."), false);
                        return 1;
                    })));
        });
    }

        private void registerMessageListener() {
            ServerMessageEvents.CHAT_MESSAGE.register((message, sender, typeKey) -> {
            // message: net.minecraft.network.message.SignedMessage
            // sender: net.minecraft.server.network.ServerPlayerEntity
            // typeKey: net.minecraft.network.message.MessageType.Parameters

            // Мы заинтересованы только в сообщениях, которые НЕ ОТ ИГРОКА
            // sender будет null для большинства системных сообщений (смерти, ачивки, /say из консоли и т.д.)
            if (sender == null) {
                // Получаем содержимое сообщения (Text) и затем его строковое представление
                String rawMessage = message.getContent().getString();

                // Получаем тип сообщения, затем его "chat" параметры, затем ключ перевода
                // Это даст нам что-то вроде "chat.type.text", "chat.type.announcement", "death.attack.generic", и т.д.
                String messageOrigin = typeKey.type().chat().translationKey();
                
                // Если вам не нравится ключ перевода, можно использовать что-то проще:
                // String messageOrigin = "Сервер"; // Просто и понятно
                // Или имя типа сообщения:
                // String messageOrigin = typeKey.type().name().toUpperCase(); // CHAT, SYSTEM, GAME_INFO

                // Простая проверка, чтобы не отправлять в Telegram сообщения, 
                // которые могли быть инициированы командой /say из этого же мода.
                // Это очень базовая проверка.
                if (rawMessage.contains(" via /say]:")) {
                     LOGGER.trace("Ignoring self-generated /say message to prevent loop: " + rawMessage);
                     return;
                }

                LOGGER.info("System message detected. Origin: {}, Content: {}", messageOrigin, rawMessage); // Для отладки
                sendToTelegram(rawMessage, true, messageOrigin);
            }
        });
    }

   private void sendToTelegram(String message, boolean addPrefix, String origin) {
    if (telegramBotToken == null || telegramChatId == null) {
        LOGGER.warn("[TelegramBridge] Telegram Bot Token или Chat ID не установлены. Сообщение НЕ отправлено: {}", message);
        return;
    }
    // ... форматирование finalMessage ...
    LOGGER.info("[TelegramBridge] Attempting to send to Telegram. ChatID: {}, Prefix: {}, Origin: {}, Message: {}", telegramChatId, addPrefix, origin, finalMessage);

    CompletableFuture.runAsync(() -> {
        try {
            // ... код запроса ...
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            LOGGER.info("[TelegramBridge] Telegram API Response Code: {}", response.statusCode());
            if (response.statusCode() != 200) {
                LOGGER.error("[TelegramBridge] Telegram API Error Response Body: {}", response.body());
            }
        } catch (Exception e) {
            LOGGER.error("[TelegramBridge] Exception during Telegram send: ", e);
        }
    });
}

        String finalMessage;
        if (addPrefix) {
            String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            // Для системных сообщений, origin может быть ключом перевода, например "death.attack.generic"
            // Или просто "Сервер", если вы так настроили
            String senderDisplay = (origin != null && !origin.isEmpty()) ? origin : "System"; 
            finalMessage = String.format("[%s] [%s]: %s", dateTime, senderDisplay, message);
        } else {
            finalMessage = message; // Для /say от игрока, уже содержит имя
        }

        CompletableFuture.runAsync(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage";
                String requestBody = "chat_id=" + URLEncoder.encode(telegramChatId, StandardCharsets.UTF_8) +
                                     "&text=" + URLEncoder.encode(finalMessage, StandardCharsets.UTF_8) +
                                     "&parse_mode=HTML"; // Или MarkdownV2

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlString))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    // LOGGER.info("Сообщение успешно отправлено в Telegram."); // Можно раскомментировать для отладки
                } else {
                    LOGGER.error("Ошибка отправки в Telegram: " + response.statusCode() + " - " + response.body());
                }
            } catch (Exception e) {
                LOGGER.error("Исключение при отправке в Telegram: ", e);
            }
        });
    }
}
