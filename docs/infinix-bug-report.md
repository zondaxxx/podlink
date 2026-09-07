# Готовый баг-репорт для Infinix (XClub / поддержка)

Отправлять в приложении XClub (раздел обратной связи или Feedback) либо на форуме
[wap.infinix.club](https://wap.infinix.club). Английский текст обычно доходит быстрее, русский приложен рядом.

---

## English

**Device:** Infinix Note 60 Ultra (X6877-RU)
**Build:** `Infinix/X6877-RU/Infinix-X6877:16/BP2A.250605.031.A3/201200035:user/release-keys`
**Android:** 16, SDK 36, SoC mt6899

**Summary:** the Bluetooth stack in XOS 16 still contains the AOSP L2CAP bug that prevents any app from
opening an L2CAP channel to Apple AirPods. Google has already fixed this upstream
(https://issuetracker.google.com/issues/371713238). Please pick up the fix in a XOS 16 OTA.

**Why this device is affected:** the Bluetooth module on this phone is a vendor build, mounted at
`/apex/com.android.bt` with version code 36, so Google Play system updates cannot deliver the fix
(32 of 38 other Mainline modules on this phone do receive Play updates). Only an Infinix OTA can fix it.

**Technical detail:** in `/apex/com.android.bt/lib64/libbluetooth_jni.so`
(sha256 `b65c64bbcc6073913814667fb121e66f34565bba67a48afea99babdd2646316a`) the symbol
`_Z22l2c_fcr_chk_chan_modesP8tL2C_CCB` at virtual address `0xc14470` still contains the old logic:

```
if (!(p_ccb->p_lcb->peer_ext_fea & L2CAP_EXTFEA_ENH_RETRANS) &&
    p_ccb->p_rcb->ertm_info.preferred_mode == L2CAP_FCR_ERTM_MODE) {
    p_ccb->p_rcb->ertm_info.preferred_mode = 0;
    return false;                    // <-- the AirPods connection dies here
}
```

AirPods do not advertise ERTM support in the L2CAP extended features, so the check fails and
`BluetoothSocket.connect()` on a classic L2CAP PSM never completes and never returns an error either.

**Reproduce:** pair AirPods Pro 2, open a classic L2CAP socket to PSM `0x1001` from any app
(`BluetoothDevice.createUsingSocketSettings` with `setSocketType(TYPE_L2CAP)` and `setL2capPsm(0x1001)`).
The call hangs indefinitely. On devices with the fixed stack the channel opens in under a second.

**Impact:** every AirPods companion app on Android (CAPod, LibrePods, Podlink) loses noise control,
1 % battery precision, conversation awareness and renaming on Infinix phones.

---

## Русский

**Устройство:** Infinix Note 60 Ultra (X6877-RU)
**Сборка:** `Infinix/X6877-RU/Infinix-X6877:16/BP2A.250605.031.A3/201200035:user/release-keys`

В Bluetooth-стеке XOS 16 остался баг AOSP, из-за которого ни одно приложение не может открыть
L2CAP-канал к наушникам Apple AirPods. Google исправил его в апстриме, тикет 371713238.
Прошу включить исправление в обновление XOS 16.

Модуль Bluetooth на этом телефоне собран Infinix и смонтирован как `/apex/com.android.bt` с кодом
версии 36, поэтому обновления системы Google Play исправление доставить не могут, хотя 32 из 38
остальных модулей Mainline на этом же телефоне через Play обновляются. Починить может только OTA Infinix.

В библиотеке `/apex/com.android.bt/lib64/libbluetooth_jni.so` функция
`_Z22l2c_fcr_chk_chan_modesP8tL2C_CCB` по адресу `0xc14470` возвращает false, когда собеседник не
заявил поддержку ERTM, а стек запросил именно ERTM. AirPods такой поддержки не заявляют, и вызов
`BluetoothSocket.connect()` к PSM `0x1001` зависает навсегда, не возвращая даже ошибку.
