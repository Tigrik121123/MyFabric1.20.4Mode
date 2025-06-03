package com.Tigrik121123.telegrambridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
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
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class TelegramBridgeMod implements ModInitializer {
    public static final String MOD_ID = "telegram_bridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static String telegramBotToken = null;
    private static String telegramChatId = null;
    private static boolean mirrorChatToTelegramEnabled = false; // Новое состояние

    // Сообщения, которые генерирует сам мод и которые не нужно зеркалировать
    private static final Set<String> SELF_GENERATED_FEEDBACK_PREFIXES = new HashSet<>(Arrays.asList(
            "Telegram Bot Token установлен.",
            "Telegram Chat ID установлен.",
            "Зеркалирование чата в Telegram ВКЛЮЧЕНО.",
            "Зеркалирование чата в Telegram ВЫКЛЮЧЕНО.",
            "Попытка отправить сообщение в Telegram через /tgsay..." // Сообщение от команды /tgsay
    ));


    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .build();

    @Override
    public void onInitialize() {
        LOGGER.info("[TelegramBridge] Mod Initializing!");
        registerCommands();
        registerMessageListeners(); // Переименуем для ясности, так как слушателей будет два
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // Команда /tgtoken
            dispatcher.register(CommandManager.literal("tgtoken") // ИЗМЕНЕНО
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("token_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        telegramBotToken = StringArgumentType.getString(context, "token_value");
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Bot Token установлен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Bot Token set to: '{}'", telegramBotToken);
                        return 1;
                    })));

            // Команда /tgchatid
            dispatcher.register(CommandManager.literal("tgchatid") // ИЗМЕНЕНО
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("chat_id_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        telegramChatId = StringArgumentType.getString(context, "chat_id_value");
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Chat ID установлен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Chat ID set to: '{}'", telegramChatId);
                        return 1;
                    })));

            // Команда /tgsay (остается)
            dispatcher.register(CommandManager.literal("tgsay")
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
                        LOGGER.info("[TelegramBridge] /tgsay command executed by '{}'. Message: '{}'", senderName, messageText);
                        // Формируем сообщение для /tgsay без префикса даты/времени, но с указанием /tgsay
                        String telegramMessage = String.format("[%s via /tgsay]: %s", senderName, messageText);
                        sendToTelegramInternal(telegramMessage); // Используем внутренний метод без доп. форматирования
                        context.getSource().sendFeedback(() -> Text.literal("Попытка отправить сообщение в Telegram через /tgsay... (см. логи сервера)"), false);
                        return 1;
                    })));

            // Команда /tgon
            dispatcher.register(CommandManager.literal("tgon")
                .requires(source -> source.hasPermissionLevel(2)) // Только админы могут включать
                .executes(context -> {
                    mirrorChatToTelegramEnabled = true;
                    context.getSource().sendFeedback(() -> Text.literal("Зеркалирование чата в Telegram ВКЛЮЧЕНО."), false);
                    LOGGER.info("[TelegramBridge] Chat mirroring to Telegram ENABLED.");
                    return 1;
                }));

            // Команда /tgoff
            dispatcher.register(CommandManager.literal("tgoff")
                .requires(source -> source.hasPermissionLevel(2)) // Только админы могут выключать
                .executes(context -> {
                    mirrorChatToTelegramEnabled = false;
                    context.getSource().sendFeedback(() -> Text.literal("Зеркалирование чата в Telegram ВЫКЛЮЧЕНО."), false);
                    LOGGER.info("[TelegramBridge] Chat mirroring to Telegram DISABLED.");
                    return 1;
                }));
        });
    }

    private void registerMessageListeners() {
        // Слушатель для сообщений чата (от игроков и некоторые системные)
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, typeKey) -> {
            if (!mirrorChatToTelegramEnabled) return; // Если зеркалирование выключено, ничего не делаем

            String rawMessage = message.getContent().getString();

            // Проверяем, не является ли это сообщение ответом от нашего же мода
            if (isSelfGeneratedFeedback(rawMessage)) {
                LOGGER.trace("[TelegramBridge] CHAT_MESSAGE: Ignoring self-generated feedback: {}", rawMessage);
                return;
            }

            // Проверяем, не является ли это сообщение результатом команды /tgsay (чтобы не дублировать)
            // Это довольно грубая проверка, но для начала сойдет.
            // Сообщение от /tgsay уже отправлено методом sendToTelegramInternal()
            if (rawMessage.startsWith("[" + (sender != null ? sender.getGameProfile().getName() : "Server") + " via /tgsay]:")) {
                 LOGGER.trace("[TelegramBridge] CHAT_MESSAGE: Ignoring /tgsay output in chat: {}", rawMessage);
                 return;
            }


            String senderName;
            String formattedMessageForTelegram;

            if (sender != null) { // Сообщение от игрока
                senderName = sender.getGameProfile().getName();
                formattedMessageForTelegram = String.format("[%s]: %s", senderName, rawMessage);
                LOGGER.info("[TelegramBridge] CHAT_MESSAGE (Player): Mirroring message from '{}': '{}'", senderName, rawMessage);
            } else { // Системное сообщение или от командного блока (sender == null)
                // messageOrigin можно получить, как раньше, если нужна детализация
                // String messageOrigin = typeKey.type().chat().translationKey();
                // Для простоты используем "System"
                senderName = "System";
                // Для системных сообщений, возможно, не нужно добавлять префикс [System], т.к. они уже отформатированы
                // Но для консистентности можно:
                formattedMessageForTelegram = String.format("[%s]: %s", senderName, rawMessage);
                LOGGER.info("[TelegramBridge] CHAT_MESSAGE (System): Mirroring system message: '{}'", rawMessage);
            }
            sendToTelegramInternal(formattedMessageForTelegram);
        });

        // Слушатель для игровых сообщений (часто это то, что появляется в action bar или системные уведомления в чате)
        ServerMessageEvents.GAME_MESSAGE.register((server, messageText, overlay) -> {
            // overlay - true, если сообщение в action bar, false - если в чате
            if (!mirrorChatToTelegramEnabled || overlay) return; // Игнорируем, если выключено или это action bar

            String rawMessage = messageText.getString();

            if (isSelfGeneratedFeedback(rawMessage)) {
                LOGGER.trace("[TelegramBridge] GAME_MESSAGE: Ignoring self-generated feedback: {}", rawMessage);
                return;
            }
            // Сообщения от /tgsay обычно не проходят через GAME_MESSAGE, но на всякий случай
             if (rawMessage.contains(" via /tgsay]:")) {
                 LOGGER.trace("[TelegramBridge] GAME_MESSAGE: Ignoring /tgsay output in chat: {}", rawMessage);
                 return;
            }

            // Эти сообщения всегда системные
            String formattedMessageForTelegram = String.format("[Game Event]: %s", rawMessage);
            LOGGER.info("[TelegramBridge] GAME_MESSAGE: Mirroring game event message: '{}'", rawMessage);
            sendToTelegramInternal(formattedMessageForTelegram);
        });
    }
    
    private boolean isSelfGeneratedFeedback(String message) {
        for (String prefix : SELF_GENERATED_FEEDBACK_PREFIXES) {
            if (message.equals(prefix)) { // Точное совпадение для фидбека
                return true;
            }
        }
        return false;
    }

    // Внутренний метод отправки без дополнительного форматирования даты/времени или префикса origin
    private void sendToTelegramInternal(String messageToSend) {
        LOGGER.info("[TelegramBridge] sendToTelegramInternal called. Message: '{}'", messageToSend);
        if (telegramBotToken == null || telegramChatId == null || telegramBotToken.isEmpty() || telegramChatId.isEmpty()) {
            LOGGER.warn("[TelegramBridge] Telegram Bot Token или Chat ID не установлены или пусты. Сообщение НЕ отправлено: {}", messageToSend);
            return;
        }

        // Для `sendToTelegramInternal` мы не добавляем префиксы даты/времени, так как формат уже задан вызывающим кодом
        // finalMessage уже является тем, что нужно отправить

        LOGGER.info("[TelegramBridge] Attempting to send to Telegram. ChatID: {}, Final Message: '{}'", telegramChatId, messageToSend);

        CompletableFuture.runAsync(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage";
                // Используем messageToSend напрямую, т.к. он уже отформатирован
                String requestBody = "chat_id=" + URLEncoder.encode(telegramChatId, StandardCharsets.UTF_8) +
                                     "&text=" + URLEncoder.encode(messageToSend, StandardCharsets.UTF_8) +
                                     "&parse_mode=HTML";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlString))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();

                LOGGER.debug("[TelegramBridge] Sending HTTP request to: {} with body: {}", urlString, requestBody);

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    LOGGER.info("[TelegramBridge] Сообщение успешно отправлено в Telegram. Response Code: {}. Original message: '{}'", response.statusCode(), messageToSend);
                } else {
                    LOGGER.error("[TelegramBridge] Telegram API Error. Status: {}, Body: {}. Original message: '{}'", response.statusCode(), response.body(), messageToSend);
                }
            } catch (Exception e) {
                LOGGER.error("[TelegramBridge] Exception during Telegram send for message '{}':", messageToSend, e);
            }
        });
    }
}
