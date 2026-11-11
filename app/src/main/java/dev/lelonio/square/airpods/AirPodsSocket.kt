// SPDX-License-Identifier: GPL-3.0-or-later
package dev.lelonio.square.airpods

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import java.io.Closeable

interface AacpTransport : Closeable {
    fun connect()
    fun readSdu(): ByteArray?
    fun write(packet: ByteArray)
}

class AacpPlatformException(val reason: String, cause: Throwable? = null) : IllegalStateException(reason, cause)

/** No class initializer loads JNI. Called only by an explicitly enabled/diagnostic session. */
object AirPodsSocket {
    private external fun enableExemptions(): Boolean
    @Synchronized private fun preparePlatform() {
        try {
            System.loadLibrary("airpods_socket")
            if (!enableExemptions()) throw AacpPlatformException("JNI Bluetooth-only exemption failed")
        } catch (error: LinkageError) {
            throw AacpPlatformException("JNI library or native entry unavailable", error)
        } catch (error: SecurityException) {
            throw AacpPlatformException("JNI platform access denied", error)
        }
    }

    @SuppressLint("MissingPermission")
    fun create(adapter: BluetoothAdapter, device: BluetoothDevice): AacpTransport {
        require(device.bondState == BluetoothDevice.BOND_BONDED) { "Select a bonded device" }
        preparePlatform()
        val uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        // TYPE_L2CAP=3 is BR/EDR; the public LE channel APIs are intentionally not used.
        val specs = listOf(
            arrayOf(adapter, device, 3, true, true, 0x1001, uuid),
            arrayOf(device, 3, true, true, 0x1001, uuid),
            arrayOf(device, 3, 1, true, true, 0x1001, uuid),
            arrayOf(3, 1, true, true, device, 0x1001, uuid),
            arrayOf(3, true, true, device, 0x1001, uuid),
        )
        var failure: Exception? = null
        for (args in specs) {
            try {
                val types = args.map { when (it) {
                    is Int -> Int::class.javaPrimitiveType!!
                    is Boolean -> Boolean::class.javaPrimitiveType!!
                    is BluetoothAdapter -> BluetoothAdapter::class.java
                    is BluetoothDevice -> BluetoothDevice::class.java
                    else -> ParcelUuid::class.java
                } }.toTypedArray()
                val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                constructor.isAccessible = true
                return SocketTransport(constructor.newInstance(*args) as BluetoothSocket)
            } catch (e: Exception) { failure = e }
        }
        throw AacpPlatformException("No accessible BR/EDR socket constructor (5 signatures attempted)", failure)
    }

    private class SocketTransport(private val socket: BluetoothSocket) : AacpTransport {
        override fun connect() = socket.connect()
        override fun readSdu(): ByteArray? {
            // Android's L2CAP read retains SDU boundaries if buffer >= negotiated receive MTU.
            val bytes = ByteArray(socket.maxReceivePacketSize.coerceAtLeast(65535))
            val count = socket.inputStream.read(bytes)
            return if (count < 0) null else bytes.copyOf(count)
        }
        @Synchronized override fun write(packet: ByteArray) { socket.outputStream.write(packet); socket.outputStream.flush() }
        override fun close() = socket.close() // deliberately no write/read monitor: interrupts blocking I/O
    }
}
