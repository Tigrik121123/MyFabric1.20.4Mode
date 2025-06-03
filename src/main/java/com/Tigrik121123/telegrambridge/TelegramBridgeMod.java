package com.Tigrik121123.telegrambridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
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

    private static Config config;
    private static File configFile;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .build();

    // Статическая ссылка на сервер, чтобы отправлять сообщения игрокам не из контекста команды
    private static MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        LOGGER.info("[TelegramBridge] Mod Initializing!");

        // Инициализация конфигурации
        File configDir = new File("config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        configFile = new File(configDir, "telegram_bridge_config.json");
        loadConfig();

        registerCommands();
        registerMessageListener();
        registerPlayerConnectionListener();

        // Сохраняем экземпляр сервера при его старте
        ServerLifecycleEvents.SERVER_STARTED.register(server -> serverInstance = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> serverInstance = null); // Очищаем при остановке
    }

    private static class Config {
        String telegramBotToken = "";
        String telegramChatId = "";
        boolean enabled = true; // По умолчанию включено

        // Пустой конструктор для Gson
        public Config() {}
    }

    private static void loadConfig() {
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, Config.class);
                if (config == null) { // Если файл пустой или некорректный JSON
                    config = new Config();
                    LOGGER.warn("[TelegramBridge] Config file was empty or malformed. Loaded default config.");
                    saveConfig(); // Сохраняем дефолтный конфиг
                } else {
                     LOGGER.info("[TelegramBridge] Config loaded. Enabled: {}, Token set: {}, ChatID set: {}",
                        config.enabled, !config.telegramBotToken.isEmpty(), !config.telegramChatId.isEmpty());
                }
            } catch (IOException e) {
                LOGGER.error("[TelegramBridge] Failed to load config file!", e);
                config = new Config(); // Загружаем дефолт в случае ошибки
            }
        } else {
            LOGGER.info("[TelegramBridge] Config file not found, creating a new one with default values.");
            config = new Config();
            saveConfig();
        }
    }

    private static void saveConfig() {
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
            LOGGER.info("[TelegramBridge] Config saved.");
        } catch (IOException e) {
            LOGGER.error("[TelegramBridge] Failed to save config file!", e);
        }
    }

    private void registerPlayerConnectionListener() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            LOGGER.info("[TelegramBridge] Player {} joined. Sending status message.", player.getGameProfile().getName());
            sendWelcomeMessage(player);

            // Отправляем сообщение о входе в Telegram
            String joinMessage = player.getGameProfile().getName() + " присоединился к игре.";
            sendToTelegram(joinMessage, true, "PlayerJoin");
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
             // Отправляем сообщение о выходе в Telegram
            String leaveMessage = player.getGameProfile().getName() + " покинул игру.";
            sendToTelegram(leaveMessage, true, "PlayerLeave");
        });
    }

    private void sendWelcomeMessage(ServerPlayerEntity player) {
        MutableText message = Text.literal("[TelegramBridge] ").formatted(Formatting.GOLD);
        if (config.telegramBotToken == null || config.telegramBotToken.isEmpty()) {
            message.append(Text.literal("Токен Telegram бота не установлен! ").formatted(Formatting.RED));
            message.append(Text.literal("[Установить]").setStyle(Style.EMPTY
                    .withFormatting(Formatting.AQUA, Formatting.UNDERLINE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/token "))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Нажмите, чтобы ввести команду /token")))));
            message.append(Text.literal(" "));
        }
        if (config.telegramChatId == null || config.telegramChatId.isEmpty()) {
            message.append(Text.literal("ID чата Telegram не установлен! ").formatted(Formatting.RED));
            message.append(Text.literal("[Установить]").setStyle(Style.EMPTY
                    .withFormatting(Formatting.AQUA, Formatting.UNDERLINE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/chatid "))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Нажмите, чтобы ввести команду /chatid")))));
        }
        if (config.telegramBotToken != null && !config.telegramBotToken.isEmpty() &&
            config.telegramChatId != null && !config.telegramChatId.isEmpty()) {
            message.append(Text.literal("Токен и ID чата установлены. Отправка ").formatted(Formatting.GREEN));
            if (config.enabled) {
                message.append(Text.literal("ВКЛЮЧЕНА.").formatted(Formatting.GREEN));
            } else {
                message.append(Text.literal("ВЫКЛЮЧЕНА.").formatted(Formatting.YELLOW));
            }
        }
        player.sendMessage(message, false); // false - не как системное сообщение в action bar
    }


    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("token")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("token_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        config.telegramBotToken = StringArgumentType.getString(context, "token_value");
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Bot Token установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Bot Token set and saved: '{}'",คอนฟิก.telegramBotToken);
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("chatid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("chat_id_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        config.telegramChatId = StringArgumentType.getString(context, "chat_id_value");
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Chat ID установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Chat ID set and saved: '{}'",คอนฟิก.telegramChatId);
                        return 1;
                    })));

            // Измененное имя команды для избежания конфликта
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
                        String telegramMessage = "[" + senderName + " via /tgsay]: " + messageText;
                        sendToTelegram(telegramMessage, false, senderName);
                        context.getSource().sendFeedback(() -> Text.literal("Попытка отправить сообщение в Telegram через /tgsay... (см. логи сервера)"), false);
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("tgtoggle")
                .requires(source -> source.hasPermissionLevel(2)) // Только для админов
                .executes(context -> {
                    config.enabled = !config.enabled;
                    saveConfig();
                    String status = config.enabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА";
                    context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram теперь " + status + "."), false);
                    LOGGER.info("[TelegramBridge] Telegram sending toggled. Now: {}", status);
                    return 1;
                }));

            dispatcher.register(CommandManager.literal("tgstatus")
                .requires(source -> source.hasPermissionLevel(0)) // Доступно всем
                .executes(context -> {
                    MutableText statusMessage = Text.literal("--- Telegram Bridge Статус ---\n").formatted(Formatting.GOLD);
                    statusMessage.append(Text.literal("Отправка сообщений: ").formatted(Formatting.YELLOW));
                    statusMessage.append(Text.literal(config.enabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА\n").formatted(config.enabled ? Formatting.GREEN : Formatting.RED));

                    statusMessage.append(Text.literal("Токен бота: ").formatted(Formatting.YELLOW));
                    if (config.telegramBotToken != null && !config.telegramBotToken.isEmpty()) {
                        // Показываем только часть токена для безопасности, если не админ
                        String partialToken = config.telegramBotToken.substring(0, Math.min(config.telegramBotToken.length(), 10)) + "...";
                        statusMessage.append(Text.literal(partialToken).formatted(Formatting.GREEN));
                        if (context.getSource().hasPermissionLevel(2)) { // Админ видит полный токен
                             statusMessage.append(Text.literal(" (Полный: " + config.telegramBotToken + ")\n").formatted(Formatting.GRAY));
                        } else {
                            statusMessage.append(Text.literal("\n"));
                        }
                    } else {
                        statusMessage.append(Text.literal("НЕ УСТАНОВЛЕН\n").formatted(Formatting.RED));
                    }

                    statusMessage.append(Text.literal("Chat ID: ").formatted(Formatting.YELLOW));
                    if (config.telegramChatId != null && !config.telegramChatId.isEmpty()) {
                        statusMessage.append(Text.literal(config.telegramChatId + "\n").formatted(Formatting.GREEN));
                    } else {
                        statusMessage.append(Text.literal("НЕ УСТАНОВЛЕН\n").formatted(Formatting.RED));
                    }
                    statusMessage.append(Text.literal("----------------------------").formatted(Formatting.GOLD));

                    context.getSource().sendFeedback(() -> statusMessage, false);
                    return 1;
                }));
        });
    }

    private void registerMessageListener() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, typeKey) -> {
            LOGGER.debug("[TelegramBridge] CHAT_MESSAGE event. Sender: {}, TypeKey: {}, Content: '{}'",
                    (sender != null ? sender.getGameProfile().getName() : "NULL_SENDER"),
                    typeKey.type().chat().translationKey(),
                    message.getContent().getString());

            // Сообщения о входе/выходе уже обрабатываются ServerPlayConnectionEvents
            // if (sender == null) {
            //    String rawMessage = message.getContent().getString();
            //    // Проверка на сообщения о входе/выходе, чтобы не дублировать
            //    if (rawMessage.contains(" присоединился к игре") || rawMessage.contains(" покинул игру")) {
            //        return; // Уже обработано другим слушателем
            //    }
            // ... остальная логика для других системных сообщений ...
            // }

            // Оставляем только для других системных сообщений (смерти, ачивки)
             if (sender == null) {
                String rawMessage = message.getContent().getString();
                String messageOrigin = typeKey.type().chat().translationKey();

                // Пропускаем сообщения, которые уже обработаны другими слушателями или являются эхом /tgsay
                if (rawMessage.contains(" via /tgsay]:") ||
                    rawMessage.contains(" присоединился к игре") || // Уже обрабатывается PlayerJoin
                    rawMessage.contains(" покинул игру")) {         // Уже обрабатывается PlayerLeave
                     LOGGER.trace("[TelegramBridge] Ignoring already handled or self-generated message: {}", rawMessage);
                     return;
                }

                LOGGER.info("[TelegramBridge] System message detected for Telegram. Origin: '{}', Content: '{}'", messageOrigin, rawMessage);
                sendToTelegram(rawMessage, true, messageOrigin);
            }
        });
    }

    private void sendToTelegram(String message, boolean addPrefix, String origin) {
        if (!config.enabled) {
            LOGGER.info("[TelegramBridge] Sending disabled globally. Message NOT sent: {}", message);
            return;
        }

        LOGGER.info("[TelegramBridge] sendToTelegram called. Message: '{}', addPrefix: {}, origin: '{}'", message, addPrefix, origin);
        if (config.telegramBotToken == null || config.telegramBotToken.isEmpty() ||
            config.telegramChatId == null || config.telegramChatId.isEmpty()) {
            LOGGER.warn("[TelegramBridge] Telegram Bot Token или Chat ID не установлены или пусты. Сообщение НЕ отправлено: {}", message);
            // Уведомляем админов в игре, если они онлайн
            if (serverInstance != null) {
                serverInstance.getPlayerManager().getPlayerList().forEach(player -> {
                    if (player.hasPermissionLevel(2)) { // Уровень оператора
                        player.sendMessage(Text.literal("[TelegramBridge] Ошибка: Токен или ChatID не установлены! Сообщение в Telegram не отправлено.").formatted(Formatting.RED), false);
                    }
                });
            }
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

        LOGGER.info("[TelegramBridge] Attempting to send to Telegram. ChatID: {}, Final Message: '{}'", config.telegramChatId, finalMessage);

        CompletableFuture.runAsync(() -> {
            try {
                String urlString = "https://api.telegram.org/bot" + config.telegramBotToken + "/sendMessage";
                String requestBody = "chat_id=" + URLEncoder.encode(config.telegramChatId, StandardCharsets.UTF_8) +
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
                LOGGER.error("[TelegramBridge] Exception during Telegram send for message '{}':", finalMessage, e);
            }
        });
    }
}
