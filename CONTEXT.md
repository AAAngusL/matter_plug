# Matter Plug

An Android app that turns a Matter Wi-Fi smart plug on and off over the home network, acting as its own Matter controller.

## Language

**Plug**:
A Matter Wi-Fi on/off smart plug that has been commissioned onto the app's **Fabric** and can be controlled by the app.
_Avoid_: device, node, accessory

**Device**:
The Android phone that runs the app.
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
