package com.hkmc.telematics.hscan.tm_interface;

/** Test fixture shaped like AIDL output: constants live in the nested Stub class. */
public interface ITM_HSCANService {
    abstract class Stub implements ITM_HSCANService {
        private static final String DESCRIPTOR = "com.hkmc.telematics.hscan.tm_interface.ITM_HSCANService";
        static final int TRANSACTION_getOdometer = 1;
        static final int TRANSACTION_setCanSignalValue = 37;
        static final int TRANSACTION_remainBatteryPercentage = 74;
        static final int TRANSACTION_reqResetSignals = 132;
        static final int TRANSACTION_getBatSoc = 137;
        static final int TRANSACTION_getXEV_Bms_GDS_SOH = 214;
        static final int TRANSACTION_bigOne = 70000;
    }
}
