package com.Tigrik121123.telegrambridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents; // Оставляем для приветственного сообщения
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
import java.util.concurrent.TimeUnit;

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
        LOGGER.info("[TelegramBridge] >>> Mod Initializing (Simplified Version) <<<");

        File configDir = new File("config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        configFile = new File(configDir, "telegram_bridge_config.json");
        loadConfig();

        registerCommands();
        registerMessageListener(); // Главный слушатель
        registerPlayerJoinListenerForWelcome(); // Только для приветственного сообщения

        ServerLifecycleEvents.SERVER_STARTED.register(server -> serverInstance = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> serverInstance = null);
        LOGGER.info("[TelegramBridge] >>> Mod Initialization Complete (Simplified Version) <<<");
    }

    private static class Config {
        String telegramBotToken = "";
        String telegramChatId = "";
        boolean enabled = true; // Отправка включена/выключена
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
                     LOGGER.info("[TelegramBridge] Config loaded. Enabled: {}, Token set: {}, ChatID set: {}",
                        config.enabled,
                        (config.telegramBotToken != null && !config.telegramBotToken.isEmpty()),
                        (config.telegramChatId != null && !config.telegramChatId.isEmpty()));
                }
            } catch (Exception e) {
                LOGGER.error("[TelegramBridge] Failed to load or parse config file! Using default config. Error: {}", e.getMessage());
                config = new Config();
                saveConfig();
            }
        } else {
            LOGGER.info("[TelegramBridge] Config file not found. Creating a new one with default values.");
            config = new Config();
            saveConfig();
        }
    }

    private static void saveConfig() {
        if (config == null) {
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

    // Слушатель только для приветственного сообщения игроку
    private void registerPlayerJoinListenerForWelcome() {
        LOGGER.info("[TelegramBridge] Registering Player Join Listener (for welcome message only).");
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            LOGGER.info("[TelegramBridge] Player {} joined. Sending welcome/status message to player.", player.getGameProfile().getName());
            sendWelcomeMessage(player);
            // Сообщение о входе в Telegram будет перехвачено ServerMessageEvents.CHAT_MESSAGE
        });
    }

    private void sendWelcomeMessage(ServerPlayerEntity player) {
        // ... (код метода sendWelcomeMessage остается таким же, как в предыдущей версии,
        // он просто информирует игрока о статусе мода) ...
        // Убедись, что команды в ClickEvent.Action.SUGGEST_COMMAND - это /tgtoken и /tgchatid
        LOGGER.info("[TelegramBridge] Preparing welcome message for player: {}", player.getGameProfile().getName());
        MutableText message = Text.literal("[TelegramBridge] ").formatted(Formatting.GOLD);
        boolean tokenSet = config.telegramBotToken != null && !config.telegramBotToken.isEmpty();
        boolean chatIdSet = config.telegramChatId != null && !config.telegramChatId.isEmpty();

        if (!tokenSet) {
            message.append(Text.literal("Токен Telegram бота не установлен! ").formatted(Formatting.RED));
            message.append(Text.literal("[Установить]").setStyle(Style.EMPTY
                    .withFormatting(Formatting.AQUA, Formatting.UNDERLINE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tgtoken ")) // Используем /tgtoken
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Нажмите, чтобы ввести команду /tgtoken")))));
            message.append(Text.literal(" "));
        }
        if (!chatIdSet) {
            message.append(Text.literal("ID чата Telegram не установлен! ").formatted(Formatting.RED));
            message.append(Text.literal("[Установить]").setStyle(Style.EMPTY
                    .withFormatting(Formatting.AQUA, Formatting.UNDERLINE)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tgchatid ")) // Используем /tgchatid
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
            // Команды /tgtoken, /tgchatid, /tgsay, /tgtoggle, /tgon, /tgoff, /tgstatus
            // остаются такими же, как в твоей последней рабочей версии (с логированием)

            dispatcher.register(CommandManager.literal("tgtoken")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("token_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        config.telegramBotToken = StringArgumentType.getString(context, "token_value");
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Bot Token установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Bot Token set and saved.");
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("tgchatid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("chat_id_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        config.telegramChatId = StringArgumentType.getString(context, "chat_id_value");
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Chat ID установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Chat ID set and saved.");
                        return 1;
                    })));

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
                        context.getSource().sendFeedback(() -> Text.literal("Попытка отправить сообщение в Telegram через /tgsay..."), false);
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("tgtoggle")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    config.enabled = !config.enabled;
                    saveConfig();
                    String status = config.enabled ? "ВКЛЮЧЕНА" : "ВЫКЛЮЧЕНА";
                    context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram теперь " + status + "."), false);
                    LOGGER.info("[TelegramBridge] Telegram sending toggled via /tgtoggle. Now: {}", status);
                    return 1;
                }));

            dispatcher.register(CommandManager.literal("tgon")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    if (!config.enabled) {
                        config.enabled = true;
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram ВКЛЮЧЕНА."), false);
                        LOGGER.info("[TelegramBridge] Telegram sending ENABLED via /tgon command.");
                    } else {
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram уже была включена."), false);
                    }
                    return 1;
                }));

            dispatcher.register(CommandManager.literal("tgoff")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(context -> {
                    if (config.enabled) {
                        config.enabled = false;
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram ВЫКЛЮЧЕНА."), false);
                        LOGGER.info("[TelegramBridge] Telegram sending DISABLED via /tgoff command.");
                    } else {
                        context.getSource().sendFeedback(() -> Text.literal("Отправка сообщений в Telegram уже была выключена."), false);
                    }
                    return 1;
                }));

            dispatcher.register(CommandManager.literal("tgstatus")
                .requires(source -> source.hasPermissionLevel(0))
                .executes(context -> {
                    LOGGER.info("[TelegramBridge] /tgstatus command executed by {}.", context.getSource().getName());
                    MutableText statusMessage = Text.literal("--- Telegram Bridge Статус ---\n").formatted(Formatting.GOLD);
                    String sendingLabel = "Отправка сообщений: ";
                    String tokenLabel   = "Токен бота:         ";
                    String chatIdLabel  = "Chat ID:            ";

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
                    // Убрали отображение sendPlayerMessages, sendDeaths, sendAchievements
                    statusMessage.append(Text.literal("\n----------------------------").formatted(Formatting.GOLD));
                    context.getSource().sendFeedback(() -> statusMessage, false);
                    return 1;
                }));
        });
        LOGGER.info("[TelegramBridge] Command registration setup complete.");
    }

    private void registerMessageListener() {
        LOGGER.info("[TelegramBridge] Registering Main Chat Message Listener.");
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, typeKey) -> {
            String senderNameText = (sender != null ? sender.getGameProfile().getName() : "NULL_SENDER");
            String messageContent = message.getContent().getString();
            String typeKeyName = typeKey.type().chat().translationKey();

            LOGGER.info("[TelegramBridge] CHAT_MESSAGE event. Sender: '{}', TypeKey: '{}', Content: '{}'",
                    senderNameText, typeKeyName, messageContent);

            if (sender == null) { // Сообщение НЕ ОТ ИГРОКА (системное, серверное)
                LOGGER.info("[TelegramBridge] System-like message detected (sender is null). TypeKey: '{}'", typeKeyName);
                
                // Фильтруем эхо от нашей команды /tgsay
                if (messageContent.contains(" via /tgsay]:")) {
                     LOGGER.trace("[TelegramBridge] Ignoring self-generated /tgsay echo: {}", messageContent);
                     return;
                }
                
                // Отправляем как есть, с префиксом даты/времени.
                // В качестве "origin" можно использовать TypeKey или просто "System"
                String origin = "System:" + typeKeyName;
                LOGGER.info("[TelegramBridge] System message for Telegram. Origin: '{}', Content: '{}'", origin, messageContent);
                sendToTelegram(messageContent, true, origin);
            } else {
                // Сообщения от игроков (sender != null) НЕ отправляем, согласно последнему уточнению.
                LOGGER.trace("[TelegramBridge] Player message from '{}' received, not sending to Telegram as per config/design.", senderNameText);
            }
        });
        LOGGER.info("[TelegramBridge] Main Chat Message Listener registered.");
    }

    // УДАЛЕНЫ методы registerLivingEntityEvents() и registerAdvancementEvents()

    private void sendToTelegram(String message, boolean addPrefix, String origin) {
        // ... (код метода sendToTelegram остается таким же, как в предыдущей версии,
        // но без parse_mode=HTML и без escapeHtml()) ...
        if (config == null) {
            LOGGER.error("[TelegramBridge] CRITICAL: Config is null! Cannot send message. Message: '{}'", message);
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
                        player.sendMessage(Text.literal("[TelegramBridge] ОШИБКА: Токен или ChatID не установлены! Сообщение в Telegram не отправлено.").formatted(Formatting.RED), false);
                    }
                });
            }
            return;
        }

        String finalMessageText;
        if (addPrefix) {
            String dateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String senderDisplay = (origin != null && !origin.isEmpty()) ? origin : "ServerEvent"; // Общее имя для источника
            finalMessageText = String.format("[%s] [%s]: %s", dateTime, senderDisplay, message);
        } else {
            finalMessageText = message; // Для /tgsay
        }

        LOGGER.info("[TelegramBridge] Preparing to send to Telegram API. ChatID: {}, Final Message: '{}'", config.telegramChatId, finalMessageText);

        CompletableFuture.runAsync(() -> {
            LOGGER.debug("[TelegramBridge] Inside CompletableFuture for sending message: '{}'", finalMessageText);
            try {
                String urlString = "https://api.telegram.org/bot" + config.telegramBotToken + "/sendMessage";
                String requestBody = "chat_id=" + URLEncoder.encode(config.telegramChatId, StandardCharsets.UTF_8) +
                                     "&text=" + URLEncoder.encode(finalMessageText, StandardCharsets.UTF_8);
                                     // УБРАН "&parse_mode=HTML"

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
