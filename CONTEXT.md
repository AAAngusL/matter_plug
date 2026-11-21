# Matter Plug

An Android app that turns a Matter Wi-Fi smart plug on and off over the home network, acting as its own Matter controller.

## Language

**Plug**:
A Matter Wi-Fi on/off smart plug that has been commissioned onto the app's **Fabric** and can be controlled by the app.
_Avoid_: device, node, accessory

**Device**:
The Android phone that runs the app. Anything the user reads calls it the "phone".
_Avoid_: using "device" for the plug

**Pairing**:
The user-facing act of adding a **Plug** to the app, from scanning its code to seeing the toggle.
_Avoid_: setup, onboarding, provisioning

**Commissioning**:
The Matter procedure that runs inside **Pairing**: secure session, credential issue, network join.
_Avoid_: using "commissioning" in anything the user reads

**Pairing mode**:
The state in which a plug accepts a new controller, entered by factory reset or opened by an existing controller.
_Avoid_: discovery mode, setup mode

**Fabric**:
The app's own Matter identity; a **Plug** belongs to it once commissioned, and only holders of its keys can control that plug.
_Avoid_: account, network, home

**Node ID**:
The address of a **Plug** on the **Fabric**.
_Avoid_: device ID, plug ID

**Shared route**:
Commissioning a plug that stays paired to another ecosystem, using a one-time code that ecosystem issues. Used for testing only.
_Avoid_: multi-admin (in user-facing text)

**Reset route**:
Commissioning a factory-reset plug over Bluetooth using its printed code. This is the product's **Pairing** flow.

**Battery Guard**:
The feature that keeps the **Device**'s battery inside a **Band** by switching the **Charger plug**.
_Avoid_: battery manager, charge controller

**Charger plug**:
The **Plug** that the **Device**'s charger is plugged into and that **Battery Guard** switches. The first **Plug** until the user chooses another; removing it switches **Battery Guard** off.
_Avoid_: power plug, smart charger

**Plugged in**:
The **Device** is connected to a power source. This is what "charging" means to **Battery Guard**, whatever the battery is doing.
_Avoid_: charging (in anything the rule depends on)

**Low threshold**:
The battery percentage at or below which the **Charger plug** is turned on.
_Avoid_: minimum, floor

**High threshold**:
The battery percentage at or above which the **Charger plug** is turned off.
_Avoid_: maximum, ceiling

**Band**:
The range between the **Low threshold** and the **High threshold**, where **Battery Guard** does nothing.
_Avoid_: window, range

**Check**:
One scheduled look at the battery level, followed by a switch if the rule asks for one.
_Avoid_: poll, scan

**Check interval**:
The time between **Checks**. One value, whether or not the **Device** is **Plugged in**.
_Avoid_: frequency, polling rate

**Alert**:
A message sent when **Battery Guard** cannot keep the battery in the **Band** or cannot do its job (the phone is not charging after the plug was turned on, the plug cannot be reached, or Battery Guard itself is faulty), when **Cellular Check** finds that SMS does not work, or when **Fixed IP** finds that the **Device**'s Wi-Fi address changed or its Wi-Fi is lost.
_Avoid_: warning, notification (a notification is only how an alert shows on the phone)

**Alert address**:
The web address that receives **Alerts**.
_Avoid_: webhook, endpoint, server

**Receiver**:
The program on a computer that listens at the **Alert address**.
_Avoid_: server, listener

**Status report**:
A short message the **Device** sends after every **Check**, when switched on, to show that **Battery Guard** is alive.
_Avoid_: heartbeat, ping

**Console check**:
The feature that tells the user, by SMS, that the autodialer console at the **Alert address** has stopped answering: it counts the **Checks** whose first push failed and sends one SMS at a limit of 2, 3 or 5. Its design document and code call it "heartbeat"; the glossary keeps that word for nothing, because a **Status report** is what shows the **Device** is alive.
_Avoid_: heartbeat, ping

**Silence alarm**:
The **Receiver**'s own alarm, raised when it hears nothing from the **Device** for longer than expected.
_Avoid_: watchdog, timeout

**Cellular Check**:
The feature that proves the **Device** can still send and receive SMS: it reads the **Passive layers** at every **Check** and confirms with a **Loopback SMS**.
_Avoid_: signal check, network monitor

**Fixed IP**:
The feature that keeps the **Device**'s Wi-Fi address from changing under the dialer that finds it by that address: the user pins the current address and reserves it on the router, and the app watches it and alerts by SMS and by the **Alert address** on a **Drift** or a lost Wi-Fi.
_Avoid_: static IP (the app cannot set one)

**Pinned address**:
The Wi-Fi address, gateway, network name and MAC the user chose to keep. One network only.
_Avoid_: locked address, saved IP

**Drift**:
The **Device** is on the pinned network but its address (or gateway) is not the pinned one.
_Avoid_: change, mismatch

**IP alert number**:
The phone number that receives **Fixed IP**'s SMS, with an optional backup number that receives the same SMS.
_Avoid_: own number (that is Cellular Check's, for its loopback)

**Layer**:
One thing that can fail on its own in **Cellular Check**: SIM, registration, signal, or SMS. A failed layer marks the layers that need it as skipped.
_Avoid_: step, stage

**Passive layer**:
The SIM, registration or signal **Layer**: read from Android without sending anything, so it costs nothing.
_Avoid_: free check, readings (in anything the rule depends on)

**Loopback SMS**:
A test SMS the **Device** sends to its own number and waits to receive back; its body starts with `MPCHK`.
_Avoid_: ping SMS, test message (unqualified)

**Confirmation loopback**:
A **Loopback SMS** sent because a **Passive layer** failed, to confirm that SMS is really unavailable. A passive failure alone never raises an **Alert**.
_Avoid_: retry

**Daily loopback**:
The one **Loopback SMS** a day sent while every **Passive layer** passes, to catch SMS faults the radio cannot show.
_Avoid_: scheduled SMS
