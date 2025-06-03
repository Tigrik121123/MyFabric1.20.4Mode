package com.Tigrik121123.telegrambridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
// import net.minecraft.network.message.MessageType; // Уже есть
// import net.minecraft.network.message.SignedMessage; // Уже есть
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

    private static MinecraftServer serverInstance;

    @Override
    public void onInitialize() {
        LOGGER.info("[TelegramBridge] Mod Initializing!");

        File configDir = new File("config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        configFile = new File(configDir, "telegram_bridge_config.json");
        loadConfig();

        registerCommands();
        registerMessageListener();
        registerPlayerConnectionListener();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> serverInstance = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> serverInstance = null);
    }

    private static class Config {
        String telegramBotToken = "";
        String telegramChatId = "";
        boolean enabled = true;
        public Config() {}
    }

    private static void loadConfig() {
        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                config = GSON.fromJson(reader, Config.class);
                if (config == null) {
                    config = new Config();
                    LOGGER.warn("[TelegramBridge] Config file was empty or malformed. Loaded default config.");
                    saveConfig();
                } else {
                     LOGGER.info("[TelegramBridge] Config loaded. Enabled: {}, Token set: {}, ChatID set: {}",
                        config.enabled, (config.telegramBotToken != null && !config.telegramBotToken.isEmpty()),
                                      (config.telegramChatId != null && !config.telegramChatId.isEmpty()));
                }
            } catch (IOException e) {
                LOGGER.error("[TelegramBridge] Failed to load config file!", e);
                config = new Config();
            }
        } else {
            LOGGER.info("[TelegramBridge] Config file not found, creating a new one with default values.");
            config = new Config();
            saveConfig();
        }
    }

    private static void saveConfig() {
        if (config == null) {
            LOGGER.error("[TelegramBridge] Attempted to save null config. This should not happen.");
            config = new Config();
        }
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
            String joinMessage = player.getGameProfile().getName() + " присоединился к игре.";
            sendToTelegram(joinMessage, true, "PlayerJoin");
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            String leaveMessage = player.getGameProfile().getName() + " покинул игру.";
            sendToTelegram(leaveMessage, true, "PlayerLeave");
        });
    }

    private void sendWelcomeMessage(ServerPlayerEntity player) {
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
             if (message.getString().endsWith(" ")) {
                MutableText temp = Text.empty();
                message.getSiblings().forEach(temp::append);
                if (!temp.getSiblings().isEmpty()) { // Ensure siblings list is not empty before accessing
                    Text lastSibling = temp.getSiblings().get(temp.getSiblings().size() - 1);
                    // Create a new MutableText to avoid modifying shared Style
                    MutableText newLastSibling = lastSibling.copy(); 
                    newLastSibling.append("\n");
                    temp.getSiblings().set(temp.getSiblings().size() - 1, newLastSibling);
                }
                message = Text.literal("[TelegramBridge] ").formatted(Formatting.GOLD).append(temp);
             } else {
                message.append(Text.literal("\n"));
             }
        }
        player.sendMessage(message, false);
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("tgtoken")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("token_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        config.telegramBotToken = StringArgumentType.getString(context, "token_value");
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Bot Token установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Bot Token set and saved: '{}'", config.telegramBotToken);
                        return 1;
                    })));

            dispatcher.register(CommandManager.literal("tgchatid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.argument("chat_id_value", StringArgumentType.greedyString())
                    .executes(context -> {
                        config.telegramChatId = StringArgumentType.getString(context, "chat_id_value");
                        saveConfig();
                        context.getSource().sendFeedback(() -> Text.literal("Telegram Chat ID установлен и сохранен."), false);
                        LOGGER.info("[TelegramBridge] Telegram Chat ID set and saved: '{}'", config.telegramChatId);
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
                        context.getSource().sendFeedback(() -> Text.literal("Попытка отправить сообщение в Telegram через /tgsay... (см. логи сервера)"), false);
                        return 1;
                    })));

            // Команда /tgtoggle (переключатель)
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

            // Команда /tgon (принудительно включить)
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

            // Команда /tgoff (принудительно выключить)
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
                    statusMessage.append(Text.literal("\n----------------------------").formatted(Formatting.GOLD));

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

            if (sender == null) { // Системные сообщения (смерти, ачивки и т.д.)
                String rawMessage = message.getContent().getString();
                String messageOrigin = typeKey.type().chat().translationKey();

                if (rawMessage.contains(" via /tgsay]:") ||
                    rawMessage.contains(" присоединился к игре") ||
                    rawMessage.contains(" покинул игру")) {
                     LOGGER.trace("[TelegramBridge] Ignoring already handled or self-generated message: {}", rawMessage);
                     return;
                }

                LOGGER.info("[TelegramBridge] System message detected for Telegram. Origin: '{}', Content: '{}'", messageOrigin, rawMessage);
                sendToTelegram(rawMessage, true, messageOrigin);
            }
        });
    }

    private void sendToTelegram(String message, boolean addPrefix, String origin) {
        if (config == null) {
            LOGGER.error("[TelegramBridge] Config is null! Cannot send message. This indicates a problem at startup.");
            return;
        }
        if (!config.enabled) {
            LOGGER.info("[TelegramBridge] Sending disabled globally. Message NOT sent: {}", message);
            return;
        }

        LOGGER.info("[TelegramBridge] sendToTelegram called. Message: '{}', addPrefix: {}, origin: '{}'", message, addPrefix, origin);
        if (config.telegramBotToken == null || config.telegramBotToken.isEmpty() ||
            config.telegramChatId == null || config.telegramChatId.isEmpty()) {
            LOGGER.warn("[TelegramBridge] Telegram Bot Token или Chat ID не установлены или пусты. Сообщение НЕ отправлено: {}", message);
            if (serverInstance != null) {
                serverInstance.getPlayerManager().getPlayerList().forEach(player -> {
                    if (player.hasPermissionLevel(2)) {
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
