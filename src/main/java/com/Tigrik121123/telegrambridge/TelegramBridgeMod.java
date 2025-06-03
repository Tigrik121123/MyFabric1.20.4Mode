package com.Tigrik121123.telegrambridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.message.MessageType; // Убедимся, что импорт есть
import net.minecraft.network.message.SignedMessage; // Убедимся, что импорт есть
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
import java.util.Arrays; // Для логирования стектрейса
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

    private static MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        LOGGER.info("[TelegramBridge] >>> Mod Initializing <<<");

        File configDir = new File("config");
        if (!configDir.exists()) {
            LOGGER.info("[TelegramBridge] Config directory 'config' not found, creating it.");
            boolean dirCreated = configDir.mkdirs();
            if (dirCreated) {
                LOGGER.info("[TelegramBridge] Config directory 'config' created successfully.");
            } else {
                LOGGER.error("[TelegramBridge] Failed to create config directory 'config'.");
            }
        }
        configFile = new File(configDir, "telegram_bridge_config.json");
        LOGGER.info("[TelegramBridge] Config file path set to: {}", configFile.getAbsolutePath());
        loadConfig();

        registerCommands();
        registerMessageListener();
        registerPlayerConnectionListener();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            LOGGER.info("[TelegramBridge] Server started. Storing server instance.");
            serverInstance = server;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[TelegramBridge] Server stopping. Clearing server instance.");
            serverInstance = null;
        });
        LOGGER.info("[TelegramBridge] >>> Mod Initialization Complete <<<");
    }

    private static class Config {
        String telegramBotToken = "";
        String telegramChatId = "";
        boolean enabled = true;
        boolean sendPlayerMessages = true; // Новая опция: отправлять ли сообщения игроков
        boolean sendDeaths = true;         // Новая опция: отправлять ли сообщения о смертях
        boolean sendAchievements = true;   // Новая опция: отправлять ли сообщения о достижениях
        // (Для sendPlayerJoinLeave мы используем отдельные события, они всегда включены, если включен мод)

        public Config() {}
    }

    private static void loadConfig() {
        LOGGER.info("[TelegramBridge] Attempting to load config from: {}", configFile.getAbsolutePath());
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, Config.class);
                if (config == null) {
                    LOGGER.warn("[TelegramBridge] Config file was empty or malformed (fromJson returned null). Creating default config.");
                    config = new Config();
                    saveConfig(); // Сохраняем дефолтный конфиг, чтобы он был валидным
                } else {
                     LOGGER.info("[TelegramBridge] Config loaded successfully. Enabled: {}, Token set: {}, ChatID set: {}, SendPlayerMessages: {}, SendDeaths: {}, SendAchievements: {}",
                        config.enabled,
                        (config.telegramBotToken != null && !config.telegramBotToken.isEmpty()),
                        (config.telegramChatId != null && !config.telegramChatId.isEmpty()),
                        config.sendPlayerMessages, config.sendDeaths, config.sendAchievements);
                }
            } catch (Exception e) { // Ловим более широкий спектр ошибок парсинга JSON
                LOGGER.error("[TelegramBridge] Failed to load or parse config file! Using default config. Error: {}", e.getMessage());
                LOGGER.debug("[TelegramBridge] Config load exception details: ", e); // Полный стектрейс в debug
                config = new Config();
                saveConfig(); // Сохраняем дефолтный, чтобы при следующем запуске был шанс
            }
        } else {
            LOGGER.info("[TelegramBridge] Config file not found at {}. Creating a new one with default values.", configFile.getAbsolutePath());
            config = new Config();
            saveConfig();
        }
    }

    private static void saveConfig() {
        if (config == null) {
            LOGGER.error("[TelegramBridge] Attempted to save null config. Initializing with defaults first.");
            config = new Config();
        }
        LOGGER.info("[TelegramBridge] Attempting to save config to: {}", configFile.getAbsolutePath());
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(config, writer);
            LOGGER.info("[TelegramBridge] Config saved successfully.");
        } catch (IOException e) {
            LOGGER.error("[TelegramBridge] Failed to save config file!", e);
        }
    }

    private void registerPlayerConnectionListener() {
        LOGGER.info("[TelegramBridge] Registering Player Connection Listener.");
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String playerName = player.getGameProfile().getName();
            LOGGER.info("[TelegramBridge] Player {} JOINED the game. Sending welcome message to player and join message to Telegram.", playerName);
            sendWelcomeMessage(player);
            String joinMessage = playerName + " присоединился к игре.";
            sendToTelegram(joinMessage, true, "PlayerJoin");
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String playerName = player.getGameProfile().getName();
            LOGGER.info("[TelegramBridge] Player {} DISCONNECTED from the game. Sending leave message to Telegram.", playerName);
            String leaveMessage = playerName + " покинул игру.";
            sendToTelegram(leaveMessage, true, "PlayerLeave");
        });
    }

    private void sendWelcomeMessage(ServerPlayerEntity player) {
        LOGGER.info("[TelegramBridge] Preparing welcome message for player: {}", player.getGameProfile().getName());
        MutableText message = Text.literal("[TelegramBridge] ").formatted(Formatting.GOLD);
        boolean tokenSet = config.telegramBotToken != null && !config.telegramBotToken.isEmpty();
        boolean chatIdSet = config.telegramChatId != null && !config.telegramChatId.isEmpty();

        if (!tokenSet) {
            message.append(Text.literal("Токен Telegram бота не установлен! ").formatted(Formatting.RED));
            message.append(Text.literal("[Установить]").setStyle(Style.EMPTY
                    .withFormatting(Formatting.AQUA, Formatting.UNDERLINE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tgtoken "))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Нажмите, чтобы ввести команду /tgtoken")))));
            message.append(Text.literal(" "));
        }
        if (!chatIdSet) {
            message.append(Text.literal("ID чата Telegram не установлен! ").formatted(Formatting.RED));
            message.append(Text.literal("[Установить]").setStyle(Style.EMPTY
                    .withFormatting(Formatting.AQUA, Formatting.UNDERLINE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tgchatid "))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Нажмите, чтобы ввести команду /tgchatid")))));
             message.append(Text.literal(" "));
        }

        if (tokenSet && chatIdSet) {
            message.append(Text.literal("Токен и ID чата установлены. Отправка ").formatted(Formatting.GREEN));
            if (config.enabled) {
                message.append(Text.literal("ВКЛЮЧЕНА.").formatted(Formatting.GREEN));
            } else {
                message.append(Text.literal("ВЫКЛЮЧЕНА.").formatted(Formatting.YELLOW));
            }
        } else if (tokenSet || chatIdSet) {
            // Убираем лишний пробел в конце, если он есть, и добавляем перенос строки
            String currentMessageString = message.getString(); // Получаем текущий текст для проверки
            if (currentMessageString.endsWith("] ")) { // Если заканчивается на "[Установить] "
                 // Удаляем последний пробел и добавляем перенос строки
                MutableText temp = Text.empty();
                for(int i=0; i < message.getSiblings().size(); i++){
                    if (i == message.getSiblings().size() -1 && message.getSiblings().get(i).getString().equals(" ")){
                        // Пропускаем последний пробел
                    } else {
                        temp.append(message.getSiblings().get(i));
                    }
                }
                message = Text.literal("[TelegramBridge] ").formatted(Formatting.GOLD).append(temp).append("\n");
            } else {
                 message.append(Text.literal("\n"));
            }
        }
        LOGGER.info("[TelegramBridge] Sending welcome message to player {}: '{}'", player.getGameProfile().getName(), message.getString());
        player.sendMessage(message, false);
    }

    private void registerCommands() {
        LOGGER.info("[TelegramBridge] Registering commands.");
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LOGGER.info("[TelegramBridge] Inside CommandRegistrationCallback.EVENT.register");

            dispatcher.register(CommandManager.literal("tgtoken")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("token_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        String newToken = StringArgumentType.getString(context, "token_value");
                        LOGGER.info("[TelegramBridge] /tgtoken command executed. New token (raw): '{}'", newToken);
                        config.telegramBotToken = newToken;
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Bot Token установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Bot Token set and saved.");
                        return 1;
                    })));
            LOGGER.info("[TelegramBridge] /tgtoken command registered.");

            dispatcher.register(CommandManager.literal("tgchatid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("chat_id_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        String newChatId = StringArgumentType.getString(context, "chat_id_value");
                        LOGGER.info("[TelegramBridge] /tgchatid command executed. New chat_id (raw): '{}'", newChatId);
                        config.telegramChatId = newChatId;
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Chat ID установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Chat ID set and saved.");
                        return 1;
                    })));
            LOGGER.info("[TelegramBridge] /tgchatid command registered.");

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
            LOGGER.info("[TelegramBridge] /tgsay command registered.");

            dispatcher.register(CommandManager.literal("tgtoggle")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    LOGGER.info("[TelegramBridge] /tgtoggle command executed. Current state: {}", config.enabled);
                    config.enabled = !config.enabled;
                    saveConfig();
                    String status = config.enabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА";
                    context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram теперь " + status + "."), false);
                    LOGGER.info("[TelegramBridge] Telegram sending toggled via /tgtoggle. Now: {}", status);
                    return 1;
                }));
            LOGGER.info("[TelegramBridge] /tgtoggle command registered.");

            dispatcher.register(CommandManager.literal("tgon")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    LOGGER.info("[TelegramBridge] /tgon command executed. Current state: {}", config.enabled);
                    if (!config.enabled) {
                        config.enabled = true;
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram ВКЛЮЧЕНА."), false);
                        LOGGER.info("[TelegramBridge] Telegram sending ENABLED via /tgon command.");
                    } else {
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram уже была включена."), false);
                        LOGGER.info("[TelegramBridge] Telegram sending was already enabled. No change by /tgon.");
                    }
                    return 1;
                }));
            LOGGER.info("[TelegramBridge] /tgon command registered.");

            dispatcher.register(CommandManager.literal("tgoff")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    LOGGER.info("[TelegramBridge] /tgoff command executed. Current state: {}", config.enabled);
                    if (config.enabled) {
                        config.enabled = false;
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram ВЫКЛЮЧЕНА."), false);
                        LOGGER.info("[TelegramBridge] Telegram sending DISABLED via /tgoff command.");
                    } else {
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram уже была выключена."), false);
                        LOGGER.info("[TelegramBridge] Telegram sending was already disabled. No change by /tgoff.");
                    }
                    return 1;
                }));
            LOGGER.info("[TelegramBridge] /tgoff command registered.");

            dispatcher.register(CommandManager.literal("tgstatus")
                .requires(source -> source.hasPermissionLevel(0))
                .executes(context -> {
                    LOGGER.info("[TelegramBridge] /tgstatus command executed by {}.", context.getSource().getName());
                    MutableText statusMessage = Text.literal("--- Telegram Bridge Статус ---\n").formatted(Formatting.GOLD);

                    String sendingLabel = "Отправка сообщений: ";
                    String tokenLabel   = "Токен бота:         ";
                    String chatIdLabel  = "Chat ID:            ";
                    String sendPlayerMsgLabel = "Отправка чата игр.: ";
                    String sendDeathsLabel    = "Отправка смертей:   ";
                    String sendAchievLabel    = "Отправка достиж.:  ";


                    statusMessage.append(Text.literal(sendingLabel).formatted(Formatting.YELLOW));
                    statusMessage.append(Text.literal(config.enabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА").formatted(config.enabled ? Formatting.GREEN : Formatting.RED));
                    statusMessage.append(Text.literal("\n"));

                    statusMessage.append(Text.literal(tokenLabel).formatted(Formatting.YELLOW));
                    if (config.telegramBotToken != null && !config.telegramBotToken.isEmpty()) {
                        String displayToken;
                        if (context.getSource().hasPermissionLevel(2)) {
                             displayToken = config.telegramBotToken;
                        } else {
                            displayToken = config.telegramBotToken.substring(0, Math.min(config.telegramBotToken.length(), 10)) +
                                           (config.telegramBotToken.length() > 10 ? "..." : "");
                        }
                        statusMessage.append(Text.literal(displayToken).formatted(Formatting.GREEN));
                    } else {
                        statusMessage.append(Text.literal("НЕ УСТАНОВЛЕН").formatted(Formatting.RED));
                    }
                    statusMessage.append(Text.literal("\n"));

                    statusMessage.append(Text.literal(chatIdLabel).formatted(Formatting.YELLOW));
                    if (config.telegramChatId != null && !config.telegramChatId.isEmpty()) {
                        statusMessage.append(Text.literal(config.telegramChatId).formatted(Formatting.GREEN));
                    } else {
                        statusMessage.append(Text.literal("НЕ УСТАНОВЛЕН").formatted(Formatting.RED));
                    }
                    statusMessage.append(Text.literal("\n"));

                    statusMessage.append(Text.literal(sendPlayerMsgLabel).formatted(Formatting.YELLOW));
                    statusMessage.append(Text.literal(config.sendPlayerMessages ? "ВКЛ" : "ВЫКЛ").formatted(config.sendPlayerMessages ? Formatting.GREEN : Formatting.RED));
                    statusMessage.append(Text.literal("\n"));
                    
                    statusMessage.append(Text.literal(sendDeathsLabel).formatted(Formatting.YELLOW));
                    statusMessage.append(Text.literal(config.sendDeaths ? "ВКЛ" : "ВЫКЛ").formatted(config.sendDeaths ? Formatting.GREEN : Formatting.RED));
                    statusMessage.append(Text.literal("\n"));

                    statusMessage.append(Text.literal(sendAchievLabel).formatted(Formatting.YELLOW));
                    statusMessage.append(Text.literal(config.sendAchievements ? "ВКЛ" : "ВЫКЛ").formatted(config.sendAchievements ? Formatting.GREEN : Formatting.RED));

                    statusMessage.append(Text.literal("\n----------------------------").formatted(Formatting.GOLD));

                    context.getSource().sendFeedback(() -> statusMessage, false);
                    return 1;
                }));
            LOGGER.info("[TelegramBridge] /tgstatus command registered.");
        });
        LOGGER.info("[TelegramBridge] Command registration setup complete.");
    }

    private void registerMessageListener() {
        LOGGER.info("[TelegramBridge] Registering Chat Message Listener.");
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, typeKey) -> {
            // Логируем абсолютно все сообщения, проходящие через этот эвент
            String senderName = (sender != null ? sender.getGameProfile().getName() : "NULL_SENDER");
            String messageContent = message.getContent().getString();
            String typeKeyName = typeKey.type().chat().translationKey();
            LOGGER.info("[TelegramBridge] CHAT_MESSAGE event received. Sender: '{}', TypeKey: '{}', RawContent: '{}'",
                    senderName, typeKeyName, messageContent);

            if (sender != null) { // Это сообщение от игрока
                if (config.sendPlayerMessages) {
                    LOGGER.info("[TelegramBridge] Processing player message from '{}'.", senderName);
                    String playerName = sender.getGameProfile().getName();
                    String playerMessageText = message.getContent().getString();

                    if (playerMessageText == null || playerMessageText.trim().isEmpty()) {
                        LOGGER.trace("[TelegramBridge] Ignoring empty player message from {}.", playerName);
                        return;
                    }
                    // Игнорируем команды, кроме /tgsay (хотя /tgsay имеет свой обработчик)
                    if (playerMessageText.startsWith("/") && !playerMessageText.toLowerCase().startsWith("/tgsay ")) {
                        LOGGER.trace("[TelegramBridge] Ignoring player command from {}: {}", playerName, playerMessageText);
                        return;
                    }
                    // Дополнительная проверка, чтобы не дублировать вывод /tgsay
                    if (playerMessageText.toLowerCase().startsWith("/tgsay ")) {
                         LOGGER.trace("[TelegramBridge] Player message is a /tgsay command, will be handled by its own executor. Ignoring here.");
                         return;
                    }

                    String formattedPlayerMessage = String.format("<%s> %s", playerName, playerMessageText);
                    LOGGER.info("[TelegramBridge] Player message for Telegram. Sender: '{}', Content: '{}'", playerName, playerMessageText);
                    sendToTelegram(formattedPlayerMessage, true, "PlayerChat:" + playerName); // Добавляем имя игрока к источнику
                } else {
                    LOGGER.trace("[TelegramBridge] sendPlayerMessages is disabled. Ignoring player message from {}.", senderName);
                }
            } else { // sender == null, это потенциально системное сообщение
                LOGGER.info("[TelegramBridge] Processing potential system message. TypeKey: '{}'", typeKeyName);
                String rawMessage = message.getContent().getString();
                String messageOrigin = typeKey.type().chat().translationKey(); // Используем translationKey как origin

                // Фильтруем уже обработанные или нежелательные сообщения
                if (rawMessage.contains(" via /tgsay]:") ||
                    rawMessage.contains(" присоединился к игре") || // Уже обрабатывается PlayerJoin
                    rawMessage.contains(" покинул игру")) {         // Уже обрабатывается PlayerLeave
                     LOGGER.trace("[TelegramBridge] System message is join/leave/tgsay_echo. Ignoring: {}", rawMessage);
                     return;
                }

                // Проверяем, включена ли отправка для этого типа сообщения
                boolean shouldSend = false;
                String eventTypeForTelegram = "System"; // Тип по умолчанию

                // Очень упрощенная проверка на смерти и достижения по ключам перевода
                // Эти ключи могут отличаться в разных версиях и локализациях!
                if (messageOrigin.startsWith("death.") && config.sendDeaths) {
                    shouldSend = true;
                    eventTypeForTelegram = "Death";
                    LOGGER.info("[TelegramBridge] Death message detected by TypeKey prefix: {}", messageOrigin);
                } else if (messageOrigin.startsWith("chat.type.advancement.") && config.sendAchievements) {
                    shouldSend = true;
                    eventTypeForTelegram = "Achievement";
                    LOGGER.info("[TelegramBridge] Advancement message detected by TypeKey prefix: {}", messageOrigin);
                } else {
                    // Если это не смерть и не достижение, и мы не хотим отправлять все подряд "системные"
                    // можно добавить дополнительную логику или просто не отправлять.
                    // Пока что, если это не смерть/достижение, мы не будем отправлять, если только не будет более общей настройки.
                    LOGGER.info("[TelegramBridge] System message (TypeKey: {}) did not match specific handlers (death/achievement) or they are disabled. Raw: {}", messageOrigin, rawMessage);
                }

                if (shouldSend) {
                    LOGGER.info("[TelegramBridge] System message FOR Telegram. Type: '{}', OriginKey: '{}', Content: '{}'", eventTypeForTelegram, messageOrigin, rawMessage);
                    sendToTelegram(rawMessage, true, eventTypeForTelegram + ":" + messageOrigin);
                }
            }
        });
        LOGGER.info("[TelegramBridge] Chat Message Listener registered.");
    }

    private void sendToTelegram(String message, boolean addPrefix, String origin) {
        if (config == null) {
            LOGGER.error("[TelegramBridge] CRITICAL: Config is null! Cannot send message. This indicates a problem at startup. Message: '{}'", message);
            return;
        }
        if (!config.enabled) {
            LOGGER.info("[TelegramBridge] Sending globally disabled by config. Message NOT sent: {}", message);
            return;
        }

        LOGGER.info("[TelegramBridge] Attempting to sendToTelegram. Raw_Msg: '{}', AddPrefix: {}, Origin: '{}'", message, addPrefix, origin);
        if (config.telegramBotToken == null || config.telegramBotToken.isEmpty() ||
            config.telegramChatId == null || config.telegramChatId.isEmpty()) {
            LOGGER.warn("[TelegramBridge] Telegram Bot Token или Chat ID не установлены или пусты. Сообщение НЕ отправлено: {}", message);
            if (serverInstance != null) {
                serverInstance.getPlayerManager().getPlayerList().forEach(player -> {
                    if (player.hasPermissionLevel(2)) {
                        player.sendMessage(Text.literal("[TelegramBridge] ОШИБКА: Токен или ChatID не установлены! Сообщение в Telegram не отправлено. Проверьте /tgstatus.").formatted(Formatting.RED), false);
                    }
                });
            }
            return;
        }

        String finalMessage;
        if (addPrefix) {
            String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String senderDisplay = (origin != null && !origin.isEmpty()) ? origin : "SystemEvent"; // Изменено для ясности
            finalMessage = String.format("[%s] [%s]: %s", dateTime, senderDisplay, message);
        } else {
            finalMessage = message;
        }

        LOGGER.info("[TelegramBridge] Preparing to send to Telegram API. ChatID: {}, Final Formatted Message: '{}'", config.telegramChatId, finalMessage);

        CompletableFuture.runAsync(() -> {
            LOGGER.debug("[TelegramBridge] Inside CompletableFuture for sending message: '{}'", finalMessage);
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

                LOGGER.debug("[TelegramBridge] Sending HTTP POST request to: {}. Request Body (urlencoded): {}", urlString, requestBody);

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                LOGGER.info("[TelegramBridge] Telegram API Response. Status: {}, Body: '{}'", response.statusCode(), response.body());

                if (response.statusCode() == 200) {
                    LOGGER.info("[TelegramBridge] Message successfully sent to Telegram. Original unformatted message: '{}'", message);
                } else {
                    LOGGER.error("[TelegramBridge] Telegram API Error sending message '{}'. Status: {}, Body: '{}'", message, response.statusCode(), response.body());
                }
            } catch (Exception e) {
                LOGGER.error("[TelegramBridge] Exception during Telegram HTTP send for message '{}'. Exception: {} | Message: {}",
                        message, e.getClass().getSimpleName(), e.getMessage());
                LOGGER.debug("[TelegramBridge] Full stack trace for Telegram send exception:", e); // Полный стектрейс в debug
            }
        }, CompletableFuture.delayedExecutor(10, java.util.concurrent.TimeUnit.MILLISECONDS)); // Небольшая задержка, чтобы логи успели записаться перед асинхронной задачей
        LOGGER.debug("[TelegramBridge] CompletableFuture for message '{}' submitted.", finalMessage);
    }
}
