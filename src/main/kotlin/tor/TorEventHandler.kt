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
 Copyright (C) 2011-2014 Sublime Software Ltd

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

import net.freehaven.tor.control.EventHandler

/**
 * Logs the data we get from notifications from the Tor OP. This is really just
 * meant for debugging.
 */
private const val UPLOADED = "UPLOADED"
private const val HS_DESC = "HS_DESC"

class TorEventHandler : EventHandler {

    private val socketMap = HashMap<String, HiddenServiceSocket>()
    private val listenerMap = HashMap<String, List<(socket: HiddenServiceSocket) -> Unit>>()

    fun attachReadyListeners(hs: HiddenServiceSocket, listeners: List<(socket: HiddenServiceSocket) -> Unit>) {
        synchronized(socketMap) {
            socketMap.put(hs.serviceName, hs)
            listenerMap.put(hs.serviceName, listeners)
        }
    }

    override fun circuitStatus(status: String, id: String, path: String) = logger.debug("CircuitStatus: $id $status $path")

    override fun streamStatus(status: String, id: String, target: String) = logger.debug("streamStatus: status: $status $id: , target: $target")

    override fun orConnStatus(status: String, orName: String) = logger.debug("OR connection: status: $status, orName: $orName")

    override fun bandwidthUsed(read: Long, written: Long) = logger.debug("bandwidthUsed: read: $read , written: $written")

    override fun newDescriptors(orList: List<String>) {

        val stringBuilder = StringBuilder("newDescriptors: ")

        orList.forEach { stringBuilder.append(it) }

        logger.debug(stringBuilder.toString())
    }

    override fun message(severity: String, msg: String) = logger.debug("message: severity: $severity , msg: $msg")

    override fun unrecognized(type: String, msg: String) {
        logger.debug("unrecognized: current: $type , $msg: msg")
        if (type == (HS_DESC) && msg.startsWith(UPLOADED)) {
            val hiddenServiceID = "${msg.split(" ")[1]}.onion"
            synchronized(socketMap) {
                val hs = socketMap[hiddenServiceID] ?: return
                logger.info("Hidden Service $hs is ready")
                listenerMap[hiddenServiceID]?.forEach { it(hs) }
                socketMap.remove(hiddenServiceID)
                listenerMap.remove(hiddenServiceID)
            }
        }
    }
}
