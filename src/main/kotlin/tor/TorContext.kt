/*
Copyright (c) 2016, 2017 Bernd Prünster
This file is part of of the unofficial Java-Tor-bindings.

Licensed under the EUPL, Version 1.1 or - as soon they will be approved by the
European Commission - subsequent versions of the EUPL (the "Licence"); You may
not use this work except in compliance with the Licence. You may obtain a copy
of the Licence at: http://joinup.ec.europa.eu/software/page/eupl

Unless required by applicable law or agreed to in writing, software distributed
under the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR
CONDITIONS OF ANY KIND, either express or implied. See the Licence for the
specific language governing permissions and limitations under the Licence.

This project includes components developed by third parties and provided under
various open source licenses (www.opensource.org).


 Copyright (c) 2014-2015 Microsoft Open Technologies, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

package tor

import net.freehaven.tor.control.TorControlConnection
import java.io.*
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// This class encapsulates data that is handled differently in Java and ANDROID as well as managing file locations.

private const val FILE_AUTH_COOKIE = ".tor/control_auth_cookie"
private const val DIR_HS_ROOT = "hiddenservice"
private const val FILE_GEOIP = "geoip"
private const val FILE_GEOIP_6 = "geoip6"
private const val FILE_TORRC = "torrc"
private const val FILE_HOSTNAME = "hostname"
private const val DIRECTIVE_GEOIP6_FILE = "GeoIPv6File "
private const val DIRECTIVE_GEOIP_FILE = "GeoIPFile "
private const val DIRECTIVE_DATA_DIRECTORY = "DataDirectory "
private const val DIRECTIVE_COOKIE_AUTH_FILE = "CookieAuthFile "

private const val STATUS_BOOTSTRAPPED = "status/bootstrap-phase"
private const val DISABLE_NETWORK = "DisableNetwork"
private const val OWNER = "__OwningControllerProcess"
private const val COOKIE_TIMEOUT = 3 * 1000 // Milliseconds


class TorController(private val socket: Socket) : TorControlConnection(socket) {

    val bootstrapped get() = getInfo(STATUS_BOOTSTRAPPED)?.contains("PROGRESS=100") ?: false

    fun shutdown() {
        socket.use {
            logger.debug("Stopping Tor")
            setConf(DISABLE_NETWORK, "1")
            shutdownTor("TERM")
        }
    }

    fun enableNetwork() = setConf(DISABLE_NETWORK, "0")
}

abstract class TorContext protected constructor(val workingDirectory: File) {
    companion object {
        @JvmStatic
        protected val FILE_TORRC_NATIVE = "torrc.native"
        @JvmStatic
        private val EVENTS = listOf("CIRC", "WARN", "ERR")

        private fun parseBootstrap(inputStream: InputStream, latch: CountDownLatch, port: AtomicReference<Int>) {
            Thread({
                Thread.currentThread().name = "NFO"
                BufferedReader(inputStream.reader()).use { reader ->
                    reader.forEachLine {
                        logger.debug { it }
                        if (it.contains("Control listener listening on port ")) {
                            port.set(Integer.parseInt(it.substring(it.lastIndexOf(" ") + 1, it.lastIndex)))
                            latch.countDown()
                        }
                    }
                }
            }).start()
        }

        private fun forwardErr(inputStream: InputStream) {
            Thread({
                Thread.currentThread().name = "ERR"
                BufferedReader(inputStream.reader()).use { reader -> reader.forEachLine { logger.error { it } } }
            }).start()
        }
    }


    protected abstract val pathToTorExecutable: String
    abstract val pathToRC: String
    protected abstract val torExecutableFileName: String
    abstract val processId: String

    internal val geoIpFile = File(workingDirectory, FILE_GEOIP)
    internal val geoIpv6File = File(workingDirectory, FILE_GEOIP_6)
    internal val torrcFile = File(workingDirectory, FILE_TORRC)
    internal val torExecutableFile get() = File(workingDirectory, torExecutableFileName)
    internal val cookieFile = File(workingDirectory, FILE_AUTH_COOKIE)

    open fun installFiles() {
        /*  This is sleazy but we have cases where an old instance of the Tor OP needs an extra second to clean itself
            up. Without that time we can't do things like delete its binary (which we currently do by default, something
            we hope to fix with https://github.com/thaliproject/Tor_Onion_Proxy_Library/issues/13   */
        Thread.sleep(1000, 0)

        workingDirectory.listFiles()?.forEach { if (it.absolutePath.startsWith(torrcFile.absolutePath)) it.delete() }

        File(workingDirectory, ".tor").let { if (it.exists()) it.deleteRecursively() }
        if (!(workingDirectory.exists() || workingDirectory.mkdirs()))
            throw RuntimeException("Could not create root directory $workingDirectory!")

        getAssetOrResourceByName(FILE_GEOIP).use { cleanInstallOneFile(it, geoIpFile) }
        getAssetOrResourceByName(FILE_GEOIP_6).use { cleanInstallOneFile(it, geoIpv6File) }
        getAssetOrResourceByName(FILE_TORRC).use { cleanInstallOneFile(it, torrcFile) }
    }

    /** Sets environment variables and working directory needed for Tor
     *  @param processBuilder we will call start on this to run Tor */
    internal fun setEnvAndWD(processBuilder: ProcessBuilder) {
        processBuilder.directory(workingDirectory)
        val environment = processBuilder.environment()
        environment["HOME"] = workingDirectory.absolutePath
        when (OsType.current) {
            OsType.LNX32, OsType.LNX64 ->
                /*  We have to provide the LD_LIBRARY_PATH because when looking for dynamic libraries
                    Linux apparently will not look in the current directory by default.
                    By setting this environment variable we fix that.   */
                environment["LD_LIBRARY_PATH"] = workingDirectory.absolutePath
        //$FALL-THROUGH$
            else -> Unit
        }
    }

    internal fun getHostNameFile(hsDir: String) = File("${getHiddenServiceDirectory(hsDir).canonicalPath}/$FILE_HOSTNAME")

    internal fun deleteAllFilesButHS() {
        // It can take a little bit for the Tor OP to detect the connection is dead and kill itself
        Thread.sleep(1000)
        workingDirectory.listFiles()?.forEach {
            if (it.isDirectory) {
                if (it.name != (DIR_HS_ROOT))
                    it.deleteRecursively()
            } else if (!it.delete())
                throw RuntimeException("Could not delete file ${it.absolutePath}")
        }
    }

    /*  Files we pull out of the AAR or JAR are typically at the root but for executables outside of ANDROID
     *  the executable for a particular platform is in a specific sub-directory.
     *  @return Path to executable in JAR Resources     */

    abstract fun generateWriteObserver(file: File): WriteObserver

    abstract fun getAssetOrResourceByName(fileName: String): InputStream

    internal fun getHiddenServiceDirectory(hsDir: String) = File(workingDirectory, "/$DIR_HS_ROOT/$hsDir")

    /** Installs all necessary files and starts the Tor OP in offline mode (e.g. networkEnabled(false)).
     *  This would only be used if you wanted to start the Tor OP so that the install and related is all done but aren't
     *  ready to actually connect it to the network.     *
     * @return True if all files installed and Tor OP successfully started
     * @throws java.io.IOException - IO Exceptions
     * @throws java.lang.InterruptedException - If we are, well, interrupted     */
    @Throws(IOException::class)
    internal fun installAndStartTorOp(bridgeConfig: List<String>, eventHandler: TorEventHandler): TorController {

        installAndConfigureFiles(bridgeConfig)

        logger.info("Starting Tor")
        val cookieFile = cookieFile
        if (!cookieFile.parentFile.exists() && !cookieFile.parentFile.mkdirs())
            throw  RuntimeException("Could not create cookieFile parent directory")

        if (!cookieFile.exists() && !cookieFile.createNewFile())
            throw  RuntimeException("Could not create cookieFile")

        val workingDirectory = workingDirectory
        // Watch for the auth cookie file being created/updated
        val cookieObserver = generateWriteObserver(cookieFile)
        // Start a new Tor process
        val torPath = torExecutableFile.absolutePath
        val configPath = torrcFile.absolutePath
        val pid = processId
        val cmd = listOf(torPath, "-f", configPath, OWNER, pid)
        val processBuilder = ProcessBuilder(cmd)
        setEnvAndWD(processBuilder)
        var torProcess: Process? = null
        var ctrlCon: TorControlConnection? = null
        try {
            torProcess = processBuilder.start()
            val controlPortCountDownLatch = CountDownLatch(1)
            val port = AtomicReference<Int>()
            parseBootstrap(torProcess.inputStream, controlPortCountDownLatch, port)
            forwardErr(torProcess.errorStream)

            /*  On platforms other than WIN we run as a daemon and so we need to wait for the process to detach or exit.
                In the case of WIN the equivalent is running as a service and unfortunately that requires managing
                the service, such as turning it off or uninstalling it when it's time to move on. Any number of errors
                can prevent us from doing the cleanup and so we would leave the process running around. Rather than do
                that on WIN we just let the process run on the exec and hence don't look for an exit code.
                This does create a condition where the process has exited due to a problem but we should hopefully
                detect that when we try to use the control connection.  */
            if (OsType.current != OsType.WIN) {
                val exit = torProcess.waitFor()
                torProcess = null
                if (exit != 0) throw IOException("Tor exited with value $exit")
            }

            // Wait for the auth cookie file to be created/updated
            if (!cookieObserver.poll(COOKIE_TIMEOUT.toLong(), TimeUnit.MILLISECONDS)) {
                workingDirectory.log()
                throw IOException("Auth cookie not created")
            }

            // Now we should be able to connect to the new process
            controlPortCountDownLatch.await()
            val sock = Socket(LOCAL_IP, port.get())

            // Open a control connection and authenticate using the cookie file
            ctrlCon = TorController(sock).apply {

                authenticate(cookieFile.readBytes())
                // Tell Tor to exit when the control connection is closed
                takeOwnership()
                resetConf(listOf(OWNER))

                setEventHandler(eventHandler)
                setEvents(EVENTS)
            }

            return ctrlCon

        } catch (e: Exception) {
            throw IOException(e)
        } finally {
            /*  It's possible that something 'bad' could happen after we executed exec but before we takeOwnership()
                in which case the Tor OP will hang out as a zombie until this process is killed.
                This is problematic when we want to do things like  */
            if (ctrlCon == null) torProcess?.destroy()
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    protected fun installAndConfigureFiles(bridgeConfig: List<String>) {

        installFiles()

        PrintWriter(FileWriter(torrcFile, true).buffered()).use { confWriter ->
            confWriter.println()
            confWriter.println(DIRECTIVE_COOKIE_AUTH_FILE + cookieFile.absolutePath)
            // For some reason the GeoIP's location can only be given as a file
            // name, not a path and it has
            // to be in the data directory so we need to set both
            confWriter.println(DIRECTIVE_DATA_DIRECTORY + workingDirectory.absolutePath)
            confWriter.println(DIRECTIVE_GEOIP_FILE + geoIpFile.name)
            confWriter.println(DIRECTIVE_GEOIP6_FILE + geoIpv6File.name)

            getAssetOrResourceByName(pathToRC).reader().buffered().use { reader ->
                confWriter.println()
                reader.forEachLine { confWriter.println(it) }

            }
            if (!bridgeConfig.isEmpty()) {
                confWriter.println()
                confWriter.println("UseBridges 1")
            }
            bridgeConfig.forEach {
                confWriter.print("Bridge ")
                confWriter.println(it)
            }
        }
    }
}