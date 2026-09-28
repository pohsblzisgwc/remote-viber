import com.remoteviber.client.network.ProtocolV2
import java.util.Base64

fun main() {
    var client: ProtocolV2? = null
    generateSequence(::readLine).forEach { line ->
        try {
            val p = line.split('\t')
            when (p[0]) {
                "HELLO" -> {
                    client = ProtocolV2(p[1], p[2])
                    val h = client!!.hello()
                    println("OK\t${h["client_pub"]}\t${h["client_nonce"]}")
                }
                "AUTH" -> {
                    val a = client!!.authenticate(mapOf("type" to "CHALLENGE", "v" to 2, "host_pub" to p[1],
                        "server_pub" to p[2], "server_nonce" to p[3], "signature" to p[4]))
                    println("OK\t${a["proof"]}")
                }
                "DECRYPT" -> println("OK\t" + Base64.getEncoder().encodeToString(client!!.decrypt(p[1].toLong(), p[2])))
                "READY" -> { client!!.acceptWelcome(); println("OK") }
                "ENCRYPT" -> {
                    val frame = client!!.encrypt(Base64.getDecoder().decode(p[1]))
                    println("OK\t${frame["seq"]}\t${frame["data"]}")
                }
                "FINGERPRINT" -> println("OK\t" + ProtocolV2.fingerprint(p[1]))
                else -> error("Unknown operation")
            }
        } catch (e: Exception) { println("ERROR\t" + (e.message ?: "Protocol error").replace('\n',' ')) }
    }
}
