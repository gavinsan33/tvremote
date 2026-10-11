package tvremote

import java.io.File
import kotlin.system.exitProcess

// Development CLI: drives an Apple TV from a computer, no phone needed.
//   tvremote pair <host[:port]>
//   tvremote <host[:port]> <button>...      e.g. up up select menu playpause
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: tvremote pair <host[:port]> | tvremote <host[:port]> <button>...")
        System.err.println("buttons: " + Button.entries.joinToString(" ") { it.name.lowercase() })
        exitProcess(2)
    }
    val verbose = System.getenv("TVREMOTE_DEBUG") != null
    val log: (String) -> Unit = { if (verbose) System.err.println(it) }
    val pairing = args[0] == "pair"
    val (host, port) = args[if (pairing) 1 else 0].split(":").let { it[0] to (it.getOrNull(1)?.toInt() ?: 49153) }
    val credFile = File(System.getProperty("user.home"), ".config/tvremote/$host.creds")
    val name = "tvremote"

    try {
        if (pairing) {
            Companion(host, port, log).use { tv ->
                val creds = tv.pair(name) {
                    print("Enter the PIN shown on the TV: ")
                    readln().trim()
                }
                credFile.parentFile.mkdirs()
                credFile.writeText(creds.toString())
                credFile.setReadable(false, false); credFile.setReadable(true, true)
                println("Paired. Credentials saved to $credFile")
            }
            return
        }
        val buttons = args.drop(1).map { arg ->
            Button.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) } ?: run {
                System.err.println("unknown button: $arg"); exitProcess(2)
            }
        }
        val creds = Credentials.parse(credFile.takeIf { it.exists() }?.readText()
            ?: run { System.err.println("not paired with $host; run: tvremote pair $host"); exitProcess(1) })
        Companion(host, port, log).use { tv ->
            tv.onEvent = { id, c -> log("event $id: $c") }
            tv.verify(creds)
            tv.startSession(creds, name)
            for (b in buttons) {
                tv.press(b)
                println("pressed ${b.name.lowercase()}")
                Thread.sleep(250)
            }
        }
    } catch (e: Exception) {
        System.err.println("error: ${e.message}")
        exitProcess(1)
    }
}
