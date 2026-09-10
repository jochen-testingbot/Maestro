package util

import org.slf4j.LoggerFactory

/**
 * Looks a physical iOS device up by UDID across both of Xcode's device stacks.
 *
 * devicectl only knows devices CoreDevice was able to pair with, i.e. iOS 17 and newer. Anything
 * older is reported — if at all — as a stub with no `hardwareProperties.udid`, so looking it up by
 * UDID there can never succeed. [XcDeviceLister] covers both generations, so it answers for the
 * rest.
 *
 * A device found that way is returned in the same [DeviceCtlResponse.Device] shape, with empty
 * [DeviceCtlResponse.ConnectionProperties]. That is not a placeholder: absent tunnel fields are
 * exactly how callers already recognise a device driven over the legacy lockdown path, which has
 * no CoreDevice tunnel to report.
 */
class RealIOSDeviceResolver(
    private val localIOSDevice: LocalIOSDevice = LocalIOSDevice(),
    private val xcDeviceLister: XcDeviceLister = XcDeviceLister(),
) {

    fun findByUdid(deviceId: String): DeviceCtlResponse.Device? {
        val fromDeviceCtl = runCatching { localIOSDevice.listDeviceViaDeviceCtl(deviceId) }.getOrNull()
        if (fromDeviceCtl != null) return fromDeviceCtl

        val fromXcDevice = runCatching { xcDeviceLister.listPhysicalIOSDevices() }
            .getOrElse { e ->
                logger.warn("Failed to look up iOS device $deviceId via xcdevice", e)
                return null
            }
            .firstOrNull { it.identifier == deviceId }
            ?: return null

        logger.info(
            "iOS device {} is not managed by CoreDevice; driving it over the legacy path ({}, iOS {}).",
            deviceId,
            fromXcDevice.modelName ?: "unknown model",
            fromXcDevice.osVersionNumber ?: "unknown version",
        )

        return DeviceCtlResponse.Device(
            identifier = deviceId,
            deviceProperties = DeviceCtlResponse.DeviceProperties(
                name = fromXcDevice.name,
                osVersionNumber = fromXcDevice.osVersionNumber,
                // xcdevice does not report Developer Mode. It cannot be inferred either: a device
                // with it disabled is still listed, it just refuses to run the test bundle later.
                developerModeStatus = null,
            ),
            hardwareProperties = DeviceCtlResponse.HardwareProperties(udid = deviceId),
            connectionProperties = DeviceCtlResponse.ConnectionProperties(),
        )
    }

    /**
     * As [findByUdid], but fails the way the old devicectl-only lookup did, so callers that cannot
     * continue without a device keep their existing error.
     */
    fun requireByUdid(deviceId: String): DeviceCtlResponse.Device =
        findByUdid(deviceId)
            ?: throw IllegalArgumentException("iOS device with identifier $deviceId not connected or available")

    private companion object {
        private val logger = LoggerFactory.getLogger(RealIOSDeviceResolver::class.java)
    }
}
