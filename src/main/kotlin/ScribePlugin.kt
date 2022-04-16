package pub.lug.mirai.plugin

import kotlinx.serialization.json.*
import net.mamoe.mirai.console.data.AutoSavePluginConfig
import net.mamoe.mirai.console.data.value
import net.mamoe.mirai.console.plugin.jvm.JvmPluginDescription
import net.mamoe.mirai.console.plugin.jvm.KotlinPlugin
import net.mamoe.mirai.contact.nameCardOrNick
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.message.data.Image.Key.queryUrl
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.utils.info
import net.mamoe.mirai.utils.warning
import okhttp3.OkHttpClient
import org.kohsuke.github.GitHubBuilder
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*

object ScribePlugin : KotlinPlugin(
    JvmPluginDescription(
        id = "pub.lug.mirai.plugin.ScribePlugin",
        name = "Scribe",
        version = "0.1.0"
    ) {
        author("shirok1")
        info(
            """
            LUG 书记，
            记录群聊消息，然后就没有然后了。
        """.trimIndent()
        )
    }
) {
    data class ScribeRecord(
        val ids: IntArray,
        val sender: String,
        val message: MessageChain,
        val time: Int
    ) {
        override fun toString(): String {
            return "$sender (${
                Instant.ofEpochSecond(time.toLong()).atZone(
                    ZoneId.of("Asia/Shanghai")
                ).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.CHINA))
            }): ${message.content}"
        }

        private var markdown: String? = null

        suspend fun toMarkdown(): String {
            if (markdown == null) markdown = renderMarkdown()
            return markdown!!
        }

        private suspend fun renderMarkdown(): String {
            return "$sender (${
                Instant.ofEpochSecond(time.toLong()).atZone(
                    ZoneId.of("Asia/Shanghai")
                ).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.CHINA))
            }): ${
                message.filterIsInstance<MessageContent>().map {
                    when (it) {
                        is Image -> "![${it.imageId}](${it.queryUrl()})"
                        is FlashImage -> "![${it.image.imageId}](${it.image.queryUrl()})"
                        is LightApp -> it.extract().run { "[$first]($second)" }
                        else -> it.content
                    }
                }.joinToString()
            }"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ScribeRecord

            if (!ids.contentEquals(other.ids)) return false

            return true
        }

        override fun hashCode(): Int {
            return ids.contentHashCode()
        }
    }

    private fun LightApp.extract(): Pair<String, String> {
        val obj = Json.parseToJsonElement(content)

        fun traverse(entry: Map.Entry<String, JsonElement>): List<Pair<String, JsonPrimitive>> {
            return when (val value = entry.value) {
                is JsonObject -> value.asIterable().flatMap { traverse(it) }
                is JsonPrimitive -> listOf(entry.key to value)
                is JsonArray -> emptyList()
            }
        }

        val url = obj.jsonObject.flatMap { traverse(it) }.firstOrNull {
            it.first.contains("url", true)
                && it.second.content.isNotEmpty()
        }?.second?.content ?: ""

        val prompt = obj.jsonObject["prompt"]?.jsonPrimitive?.content ?: content

        return prompt to url
    }

    private fun GroupMessageEvent.record(): ScribeRecord {
//        return ScribeRecord(source.ids, senderName, message, time)
        return ScribeRecord(source.ids, Config.qqGitHub.getOrDefault(sender.id, senderName), message, time)
    }

    private fun QuoteReply.record(senderName: String, cache: CircularArray<ScribeRecord>? = null): ScribeRecord {
        return cache?.firstOrNull { it.ids.contentEquals(source.ids) }
            ?: ScribeRecord(source.ids, senderName, source.originalMessage, source.time)
    }

    private val messageCache: MutableMap<Long, CircularArray<ScribeRecord>> = mutableMapOf()

    private const val RING_CACHE_SIZE = 1024
    private fun cacheFor(group: Long): CircularArray<ScribeRecord> {
        return messageCache.getOrPut(group) { CircularArray(RING_CACHE_SIZE) }
    }

    private fun GroupMessageEvent.getCache(): CircularArray<ScribeRecord> = cacheFor(group.id)

    object Config : AutoSavePluginConfig("Map") {
        val groupRepo: MutableMap<Long, String> by value()
        val qqGitHub: MutableMap<Long, String> by value()
    }

    private val ghClient = GitHubBuilder.fromPropertyFile().withConnector(
        OkHttpGitHubConnector(
            OkHttpClient.Builder()
//                .cache(Cache(cacheDirectory, 10 * 1024 * 1024))
                .build()
        )
    ).build()

    private fun appendToIssue(text: String, group: Long) {
        val repoName = Config.groupRepo.getOrDefault(group, null)
        if (repoName == null) {
            logger.warning { "群 $group 无对应 GitHub 仓库" }
            return
        }
        val repo = ghClient.getRepository(repoName)
        if (repo == null) {
            logger.warning { "无法访问 GitHub 仓库" }
            return
        }

        repo.queryIssues().list()
            .filter { it.labels.any { ghLabel -> ghLabel.name == "collecting" } }
            .sortedByDescending { it.createdAt }.firstOrNull()?.comment(text)
    }

    private suspend fun GroupMessageEvent.saveMessage(message: ScribeRecord) =
        appendToIssue(message.toMarkdown(), group.id)

    private suspend fun GroupMessageEvent.saveMessages(messages: Iterable<ScribeRecord>) =
        appendToIssue(messages.map { it.toMarkdown() }.joinToString("\n"), group.id)

    private var lastMessage: MessageChain? = null

    override fun onEnable() {
        Config.reload()
        logger.info { "书记插件已加载" }
        logger.info { "群组与 Repo 对应关系：" + Config.groupRepo.map { "${it.key}: ${it.value}" }.joinToString("; ") }
        logger.info { "QQ 号与 GitHub 用户名对应关系：" + Config.qqGitHub.map { "${it.key}: ${it.value}" }.joinToString("; ") }
        val eventChannel = GlobalEventChannel.parentScope(this)
        eventChannel.subscribeAlways<GroupMessageEvent> {
            if (!message.any { it is At && it.target == this.bot.id }) {
                getCache().add(record()) // Cache
            } else {
                val quote = message.firstIsInstanceOrNull<QuoteReply>()
                if (quote != null) when {
                    message.content.contains("保存") -> {
                        //保存单条消息
                        val record = quote.record(
                            Config.qqGitHub.getOrDefault(quote.source.fromId, null)
                                ?: getCache().firstOrNull { it.ids.contentEquals(quote.source.ids) }?.sender
                                ?: group[quote.source.fromId]?.nameCardOrNick ?: "佚名",
                            getCache()
                        )
                        saveMessage(record)
                        group.sendMessage(buildMessageChain {
                            //+this@subscribeAlways.message.quote()
                            //+PlainText("保存“${quote.source.originalMessage}”成功")
                            +"保存成功，内容如下："
                            +record.toMarkdown()
                        })
                    }

                    else -> {
                        //保存多条消息，结束标识
                        val toSave = getCache()
                            .dropWhile { !it.ids.contentEquals(quote.source.ids) }
                            //.takeWhile { it != lastMessage }
                            .toList()
                        if (!toSave.any()) {
                            group.sendMessage(buildMessageChain {
                                +"貌似没有可以保存的东西……"
                            })
                        } else {
                            saveMessages(toSave)
                            group.sendMessage(buildMessageChain {
                                +"保存内容一览：\n"
                                toSave.forEach {
                                    +(it.toMarkdown() + "\n")
                                }
                            })
                        }
                    }
                }
                else {
                    if (message.any { it is PlainText && it.content.contains("浏览") }) {
                        group.sendMessage(buildMessageChain {
                            val cache = getCache()
                            if (cache.any()) {
                                +"已经记录下的东西：\n"
                                cache.forEach {
                                    +(it.toMarkdown() + "\n")
                                }
                            } else {
                                +"目前在这个群还没有记录……"
                            }
                        })
                    } else {
                        //开始保存多条消息
                        lastMessage = message
                        group.sendMessage(buildMessageChain {
                            +this@subscribeAlways.message.quote()
                            +"请选择要保存的范围，回复最早的一条消息并@我"
                        })
                    }

                }
            }
        }
    }
}
