package tor

import com.beust.jcommander.JCommander
import com.beust.jcommander.Parameter
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.*


class Demo {

    @Parameter(names = ["-b"], description = "path to a file containing bridge configuration lines as obtainable from bridges.torproject.org")
    internal var pathBridges: String? = null

    @Parameter(names = ["-p"], description = "hidden Service Port")
    internal var port: Int? = null
}

fun main(args: Array<String>) {
    val demo = Demo()
    demo.port = 10024
    JCommander(demo).parse(*args)

    //set default instance, so it can be omitted whenever creating Tor (Server)Sockets. This will take some time
    Tor.default = NativeTor(
            File("tor-demo"), // Tor installation destination
            parseBridgeLines(demo.pathBridges)) // bridge configuration
    println("Tor has been bootstrapped")

    //create a hidden service in directory 'test' inside the tor installation directory
    val hiddenServiceSocket = HiddenServiceSocket(demo.port!!, "test")

    //it takes some time for a hidden service to be ready, so adding a listener only after creating the HS is not an issue
    hiddenServiceSocket.addReadyListener { socket ->

        println("Hidden Service $socket is ready")
        Thread({
            System.err.println("we'll try and connect to the just-published hidden service")
            TorSocket(socket.serviceName, socket.hiddenServicePort, streamId = "Foo")
            System.err.println("Connected to $socket. closing socket...")
            socket.close()
            //retry connecting
            try {
                TorSocket(socket.serviceName, socket.hiddenServicePort, streamId = "Foo")
            } catch (e: Exception) {
                System.err.println("As exptected, connection to $socket failed!")
            }
            //let's connect to some regular domains using different streams
            TorSocket("www.google.com", 80, streamId = "FOO")
            TorSocket("www.cnn.com", 80, streamId = "BAR")
            TorSocket("www.google.com", 80, streamId = "BAZ")

            System.exit(0)

        }).start()
        socket.accept()
        System.err.println("$socket got a connection")
    }
    System.err.println("It will take some time for the HS to be reachable (up to 40 seconds). You will be notified about this")
    Scanner(System.`in`).nextLine()
}


private fun parseBridgeLines(file: String?) = file?.let { BufferedReader(FileReader(file)).use { it.readLines() } }
