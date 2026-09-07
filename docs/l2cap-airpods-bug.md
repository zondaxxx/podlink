# Почему AirPods не отдают AAP на Android 16 (Infinix X6877, XOS 16)

Разбор сделан по реальной библиотеке с телефона:
`/apex/com.android.bt/lib64/libbluetooth_jni.so`, 14 873 072 байта,
sha256 `b65c64bbcc6073913814667fb121e66f34565bba67a48afea99babdd2646316a`,
прошивка `Infinix/X6877-RU/Infinix-X6877:16/BP2A.250605.031.A3/201200035:user/release-keys`.

## Где сидит проблема

Символ найден в сжатой XZ-секции `.gnu_debugdata`:

```
_Z22l2c_fcr_chk_chan_modesP8tL2C_CCB   vaddr 0x00c14470   size 244
```

Секция `.text` имеет `addr == off == 0x2d8000`, поэтому смещение в файле совпадает с адресом: **0xc14470**.

Дизассемблированная логика (ARM64):

```asm
c144c0:  ldr   x8, [x19, #0x40]     ; p_ccb->p_lcb
c144c4:  ldrb  w8, [x8, #0x58]      ; peer_ext_fea
c144c8:  tbnz  w8, #3, 0xc14534     ; бит 3 = L2CAP_EXTFEA_ENH_RETRANS -> return true
c144cc:  ldr   x8, [x19, #0x60]     ; p_ccb->p_rcb
c144d0:  ldrb  w8, [x8, #0x78]      ; ertm_info.preferred_mode
c144d4:  cmp   w8, #3               ; L2CAP_FCR_ERTM_MODE
c144d8:  b.ne  0xc14534             ; не ERTM -> return true
         ... log::warn("Peer does not support our desired channel types") ...
c14528:  mov   w0, wzr              ; return false   <-- здесь обрывается соединение
c1452c:  strb  wzr, [x8, #0x78]     ; preferred_mode = 0
c14534:  mov   w0, #1               ; return true
```

Условие отказа ровно одно: собеседник не заявил поддержку ERTM в расширенных возможностях L2CAP,
а стек просит именно ERTM. AirPods на запрос Information Request не отвечают так, как ждёт Fluoride,
поэтому `peer_ext_fea` остаётся нулём, функция возвращает false, и `connect()` не завершается никогда.
Это и есть [Google issue 371713238](https://issuetracker.google.com/issues/371713238).

Приложение не управляет режимом канала: `BluetoothSocketSettings` в API 36 даёт только PSM, тип сокета,
шифрование и аутентификацию. Значит без вмешательства в сам стек обойти нельзя.

## Что с этим делать

* **Обновление Google Play не поможет.** Модуль Bluetooth на этом телефоне собран Infinix:
  APEX смонтирован как `/apex/com.android.bt`, код версии 36 (уровень API), а не номер ветки Mainline.
  При этом 32 из 38 остальных модулей Play обновляет, то есть механизм работает, Bluetooth просто вне его.
* **Root.** Модуль librepods для LSPosed хукает эту самую функцию в процессе Bluetooth и заставляет её
  вернуть true. Хук глобальный, после него AAP заработает и в Podlink. Патч соответствует байтам
  `20 00 80 52 C0 03 5F D6` (`mov w0, #1; ret`) в начале функции.
* **Android 17**, где исправление приедет всем.

## Как проверить на своём телефоне

Лаборатория → Root-диагностика → «Проверить стек». Приложение само разбирает ELF, распаковывает
`.gnu_debugdata`, находит функцию и показывает вердикт: `stock, bug present` или `patched`.
