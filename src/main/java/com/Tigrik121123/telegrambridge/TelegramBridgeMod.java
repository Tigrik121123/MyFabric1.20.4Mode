package com.Tigrik121123.telegrambridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.advancement.v1.ServerAdvancementEvents; // Для достижений
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents; // Для смертей
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.advancement.AdvancementDisplay;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.advancement.AdvancementFrame;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
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
import java.util.concurrent.TimeUnit; // Для delayedExecutor

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
            if (dirCreated) LOGGER.info("[TelegramBridge] Config directory 'config' created successfully.");
            else LOGGER.error("[TelegramBridge] Failed to create config directory 'config'.");
        }
        configFile = new File(configDir, "telegram_bridge_config.json");
        LOGGER.info("[TelegramBridge] Config file path set to: {}", configFile.getAbsolutePath());
        loadConfig();

        registerCommands();
        registerMessageListener();
        registerPlayerConnectionListener();
        registerLivingEntityEvents();
        registerAdvancementEvents();

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
        boolean sendPlayerMessages = true;
        boolean sendDeaths = true;
        boolean sendAchievements = true;
        public Config() {}
    }

    private static void loadConfig() {
        LOGGER.info("[TelegramBridge] Attempting to load config from: {}", configFile.getAbsolutePath());
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, Config.class);
                if (config == null) {
                    LOGGER.warn("[TelegramBridge] Config file was empty or malformed. Creating default config.");
                    config = new Config();
                    saveConfig();
                } else {
                     LOGGER.info("[TelegramBridge] Config loaded successfully. Enabled: {}, Token set: {}, ChatID set: {}, SendPlayerMessages: {}, SendDeaths: {}, SendAchievements: {}",
                        config.enabled, (config.telegramBotToken != null && !config.telegramBotToken.isEmpty()),
                        (config.telegramChatId != null && !config.telegramChatId.isEmpty()),
                        config.sendPlayerMessages, config.sendDeaths, config.sendAchievements);
                }
            } catch (Exception e) {
                LOGGER.error("[TelegramBridge] Failed to load or parse config file! Using default config. Error: {}", e.getMessage());
                LOGGER.debug("[TelegramBridge] Config load exception details: ", e);
                config = new Config();
                saveConfig();
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
            String currentMessageString = message.getString();
            if (currentMessageString.endsWith("] ")) {
                MutableText temp = Text.empty();
                for(int i=0; i < message.getSiblings().size(); i++){
                    if (i == message.getSiblings().size() -1 && message.getSiblings().get(i).getString().equals(" ")){
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
                        sendToTelegram(telegramMessage, false, senderName); // false - не добавлять префикс времени/даты
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
            String senderName = (sender != null ? sender.getGameProfile().getName() : "NULL_SENDER");
            String messageContent = message.getContent().getString();
            // MessageType.Parameters type = typeKey; // Старое имя
            String typeKeyName = typeKey.type().chat().translationKey(); // Используем typeKey напрямую

            LOGGER.info("[TelegramBridge] CHAT_MESSAGE event received. Sender: '{}', TypeKey: '{}', RawContent: '{}'",
                    senderName, typeKeyName, messageContent);

            if (sender != null) { // Сообщение от игрока
                if (config.sendPlayerMessages) {
                    LOGGER.info("[TelegramBridge] Processing player message from '{}'.", senderName);
                    // String playerName = sender.getGameProfile().getName(); // Уже есть в senderName
                    String playerMessageText = message.getContent().getString();

                    if (playerMessageText == null || playerMessageText.trim().isEmpty()) {
                        LOGGER.trace("[TelegramBridge] Ignoring empty player message from {}.", senderName);
                        return;
                    }
                    if (playerMessageText.startsWith("/") && !playerMessageText.toLowerCase().startsWith("/tgsay ")) {
                        LOGGER.trace("[TelegramBridge] Ignoring player command from {}: {}", senderName, playerMessageText);
                        return;
                    }
                    if (playerMessageText.toLowerCase().startsWith("/tgsay ")) {
                         LOGGER.trace("[TelegramBridge] Player message is a /tgsay command, will be handled by its own executor. Ignoring here.");
                         return;
                    }

                    String formattedPlayerMessage = String.format("<%s> %s", senderName, playerMessageText);
                    LOGGER.info("[TelegramBridge] Player message for Telegram. Sender: '{}', Content: '{}'", senderName, playerMessageText);
                    sendToTelegram(formattedPlayerMessage, true, "PlayerChat:" + senderName);
                } else {
                    LOGGER.trace("[TelegramBridge] sendPlayerMessages is disabled. Ignoring player message from {}.", senderName);
                }
            } else { // sender == null, системное сообщение
                LOGGER.info("[TelegramBridge] Processing potential system message (sender == null). TypeKey: '{}'", typeKeyName);
                String rawMessage = message.getContent().getString();
                // String messageOrigin = typeKey.type().chat().translationKey(); // Уже есть в typeKeyName

                // Смерти и достижения теперь обрабатываются отдельными слушателями.
                // Этот блок теперь для ДРУГИХ системных сообщений, если они есть и их нужно отправлять.
                // Например, сообщения от командных блоков без указания игрока, /say из консоли сервера (не /tgsay)

                // Фильтруем то, что уже точно обработано другими слушателями или не нужно
                if (typeKeyName.startsWith("death.") || // Смерти обрабатываются ServerLivingEntityEvents
                    typeKeyName.startsWith("chat.type.advancement.") || // Ачивки обрабатываются ServerAdvancementEvents
                    rawMessage.contains(" via /tgsay]:") || // Эхо нашей команды
                    rawMessage.contains(" присоединился к игре") || // Обрабатывается PlayerJoin
                    rawMessage.contains(" покинул игру")) {         // Обрабатывается PlayerLeave
                     LOGGER.trace("[TelegramBridge] System message (sender == null) is likely handled elsewhere or is an echo. TypeKey: '{}'. Ignoring: {}", typeKeyName, rawMessage);
                     return;
                }
                // Если сюда дошло какое-то ДРУГОЕ системное сообщение:
                // Можно добавить опцию config.sendOtherSystemMessages
                // if (config.sendOtherSystemMessages) {
                //    LOGGER.info("[TelegramBridge] Other System message FOR Telegram. OriginKey: '{}', Content: '{}'", typeKeyName, rawMessage);
                //    sendToTelegram(rawMessage, true, "System:" + typeKeyName);
                // } else {
                LOGGER.info("[TelegramBridge] Unhandled system message (sender == null) detected. OriginKey: '{}', Content: '{}'. Not configured for sending.", typeKeyName, rawMessage);
                // }
            }
        });
        LOGGER.info("[TelegramBridge] Chat Message Listener registered.");
    }

    private void registerLivingEntityEvents() {
        LOGGER.info("[TelegramBridge] Registering Living Entity Events Listener (for deaths).");
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            LOGGER.info("[TelegramBridge] LIVING_ENTITY_AFTER_DEATH event fired. EntityType: {}, DamageSource: {}", entity.getType().getName().getString(), damageSource.getName());
            if (entity instanceof ServerPlayerEntity && config.sendDeaths) {
                ServerPlayerEntity player = (ServerPlayerEntity) entity;
                String playerName = player.getGameProfile().getName();
                Text deathMessageText = damageSource.getDeathMessage(player);
                String deathMessageString;

                if (deathMessageText != null) {
                    deathMessageString = deathMessageText.getString();
                } else {
                    deathMessageString = playerName + " умер от неизвестной причины (" + damageSource.getName() + ")";
                    LOGGER.warn("[TelegramBridge] Could not get standard death message for {}, source {}. Using fallback.", playerName, damageSource.getName());
                }
                
                LOGGER.info("[TelegramBridge] Player death detected for Telegram. Player: '{}', Message: '{}'", playerName, deathMessageString);
                sendToTelegram(deathMessageString, true, "PlayerDeath:" + damageSource.getName().replace('.', '_')); // Заменяем точки в имени источника урона
            } else if (entity instanceof ServerPlayerEntity) {
                LOGGER.trace("[TelegramBridge] Player death detected, but sendDeaths is disabled. Player: {}", ((ServerPlayerEntity)entity).getGameProfile().getName());
            }
        });
    }

    private void registerAdvancementEvents() {
        LOGGER.info("[TelegramBridge] Registering Advancement Events Listener.");
        ServerAdvancementEvents.ADVANCEMENT_GRANTED.register((player, advancementEntry) -> {
            String playerName = player.getGameProfile().getName();
            String advancementId = advancementEntry.id().toString();
            LOGGER.info("[TelegramBridge] ADVANCEMENT_GRANTED event fired. Player: {}, Advancement ID: {}", playerName, advancementId);

            if (config.sendAchievements) {
                AdvancementDisplay display = advancementEntry.value().display().orElse(null);
                if (display != null && display.shouldAnnounceToChat()) {
                    String advancementTitle = display.getTitle().getString();
                    String advancementMessage;
                    if (display.getFrame() == AdvancementFrame.CHALLENGE) {
                        advancementMessage = String.format("%s выполнил испытание [%s]", playerName, advancementTitle);
                    } else {
                        advancementMessage = String.format("%s получил достижение [%s]", playerName, advancementTitle);
                    }
                    LOGGER.info("[TelegramBridge] Advancement granted for Telegram. Player: '{}', Title: '{}', FullMsg: '{}'", playerName, advancementTitle, advancementMessage);
                    sendToTelegram(advancementMessage, true, "Advancement:" + advancementEntry.id().getPath().replace('/', '_')); // Заменяем / в пути
                } else {
                    LOGGER.trace("[TelegramBridge] Advancement '{}' for player {} should not be announced or has no display. Ignoring.", advancementId, playerName);
                }
            } else {
                 LOGGER.trace("[TelegramBridge] Advancement granted, but sendAchievements is disabled. Player: {}, Advancement: {}", playerName, advancementId);
            }
        });
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

        String finalMessageText;
        if (addPrefix) {
            String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String senderDisplay = (origin != null && !origin.isEmpty()) ? origin : "SystemEvent";
            finalMessageText = String.format("[%s] [%s]: %s", dateTime, senderDisplay, message);
        } else {
            finalMessageText = message;
        }

        // Экранирование HTML не нужно, так как parse_mode не используется
        // String escapedFinalMessage = escapeHtml(finalMessageText); // УДАЛЕНО

        LOGGER.info("[TelegramBridge] Preparing to send to Telegram API. ChatID: {}, Final Message: '{}'", config.telegramChatId, finalMessageText);

        CompletableFuture.runAsync(() -> {
            LOGGER.debug("[TelegramBridge] Inside CompletableFuture for sending message: '{}'", finalMessageText);
            try {
                String urlString = "https://api.telegram.org/bot" + config.telegramBotToken + "/sendMessage";
                // Убираем parse_mode=HTML, чтобы текст отправлялся как есть
                String requestBody = "chat_id=" + URLEncoder.encode(config.telegramChatId, StandardCharsets.UTF_8) +
                                     "&text=" + URLEncoder.encode(finalMessageText, StandardCharsets.UTF_8);
                                     // "&parse_mode=HTML" - УДАЛЕНО

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
                LOGGER.error("[TelegramBridge] Exception during Telegram HTTP send for message (original): '{}'. Exception: {} | Message: {}",
                        message, e.getClass().getSimpleName(), e.getMessage());
                LOGGER.debug("[TelegramBridge] Full stack trace for Telegram send exception:", e);
            }
        }, CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS));
        LOGGER.debug("[TelegramBridge] CompletableFuture for message '{}' submitted.", finalMessageText);
    }
}
