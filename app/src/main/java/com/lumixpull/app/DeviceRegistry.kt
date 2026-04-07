package com.lumixpull.app

import android.hardware.usb.UsbDevice

enum class TransferMode {
    MTP,      // PTP/MTP protocol (most cameras)
    VOLUME    // USB Mass Storage / mounted filesystem (action cams, drones)
}

data class DeviceProfile(
    val vendorId: Int,
    val brand: String,
    val transferMode: TransferMode,
    val subfolder: String,
    val mtpQuirks: MtpQuirks = MtpQuirks()
)

data class MtpQuirks(
    val useAllStoragesWildcard: Boolean = false,
    val requiresTetherMode: Boolean = false
)

object DeviceRegistry {
    private val KNOWN_DEVICES = listOf(
        DeviceProfile(0x04DA, "Lumix",      TransferMode.MTP,    "Lumix",      MtpQuirks(useAllStoragesWildcard = true, requiresTetherMode = true)),
        DeviceProfile(0x2CA3, "DJI",        TransferMode.VOLUME, "DJI"),
        DeviceProfile(0x2672, "GoPro",      TransferMode.MTP,    "GoPro"),
        DeviceProfile(0x04A9, "Canon",      TransferMode.MTP,    "Canon"),
        DeviceProfile(0x04B0, "Nikon",      TransferMode.MTP,    "Nikon"),
        DeviceProfile(0x054C, "Sony",       TransferMode.MTP,    "Sony"),
        DeviceProfile(0x04CB, "Fujifilm",   TransferMode.MTP,    "Fujifilm"),
        DeviceProfile(0x07B4, "Olympus",    TransferMode.MTP,    "Olympus"),
        DeviceProfile(0x1EDB, "Blackmagic", TransferMode.VOLUME, "Blackmagic"),
        DeviceProfile(0x2E1A, "Insta360",   TransferMode.VOLUME, "Insta360"),
        DeviceProfile(0x1A98, "Leica",      TransferMode.MTP,    "Leica"),
        DeviceProfile(0x2756, "Hasselblad", TransferMode.MTP,    "Hasselblad"),
        DeviceProfile(0x05CA, "Ricoh",      TransferMode.MTP,    "Ricoh"),
    )

    fun findByVendorId(vendorId: Int): DeviceProfile? =
        KNOWN_DEVICES.firstOrNull { it.vendorId == vendorId }

    /** Auto-detect transfer mode from USB interface classes when device is unknown */
    fun detectTransferMode(device: UsbDevice): TransferMode {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 6) return TransferMode.MTP     // Still Image / PTP
            if (iface.interfaceClass == 8) return TransferMode.VOLUME  // Mass Storage
        }
        return TransferMode.MTP // Default fallback
    }

    fun allVendorIds(): List<Int> = KNOWN_DEVICES.map { it.vendorId }
}
