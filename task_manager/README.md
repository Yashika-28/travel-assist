# Task Manager Android App (Jetpack Compose)

A modern, highly polished, dark-themed Task Manager/Todo application built using Kotlin and Jetpack Compose (Material 3).

---

## 🛠️ Project Structure

- **[`MainActivity.kt`](file:///C:/Users/Nischal%20Sharma/task_manager/app/src/main/java/com/example/taskmanager/MainActivity.kt)**: Houses the complete UI logic, state management, custom Dark Theme, and animations for the app.
- **[`AndroidManifest.xml`](file:///C:/Users/Nischal%20Sharma/task_manager/app/src/main/AndroidManifest.xml)**: Declares the app configuration, theme, launcher activity, and resources.
- **Gradle Configurations**: Version catalog located in [`libs.versions.toml`](file:///C:/Users/Nischal%20Sharma/task_manager/gradle/libs.versions.toml) containing dependencies for Compose BOM, Kotlin 2.0, and SDK version 35.

---

## ⚙️ Setup & Installations

Since Android development relies on specific SDKs, tools, and a JDK, the easiest and most robust way to build and run this app is by installing **Android Studio**. It bundles everything you need (JDK, Android SDK, Gradle runner, Emulator, Layout Editor, etc.) automatically.

### Step 1: Install Android Studio
1. Download Android Studio from the official site: **[https://developer.android.com/studio](https://developer.android.com/studio)**.
2. Run the installer and proceed with the **Standard Installation**.
3. During setup, the installer will automatically download the **Android SDK**, **Android SDK Command-line Tools**, and a **JDK** (Java Development Kit).

### Step 2: Open this Project
1. Launch Android Studio.
2. Click **Open** (or **File > Open**).
3. Navigate to your workspace directory: `C:\Users\Nischal Sharma\task_manager`.
4. Select the folder and click **OK**.
5. Wait for Android Studio to index your project and download the corresponding Gradle distribution (it will sync automatically).

---

## 🚀 How to Run the App

You can run this application using either an **Emulator** (virtual phone) or a **Physical Android Device**.

### Option A: Running on a Virtual Device (Emulator)
1. In Android Studio, open the **Device Manager** (located on the right sidebar or via **Tools > Device Manager**).
2. Click **Create device**.
3. Choose a device definition (e.g., `Pixel 8` or `Pixel 9`) and click **Next**.
4. Select a system image (e.g., **API 35** or **API 34**) and click **Download** next to it.
5. Once downloaded, click **Next** and then **Finish**.
6. Launch the emulator by clicking the green Play button next to it.
7. Click the green **Run** button (`Shift + F10` or play icon) in the top toolbar of Android Studio to build and launch the app on your emulator.

### Option B: Running on a Physical Android Device
1. On your Android phone, go to **Settings > About phone**.
2. Tap **Build number** 7 times to enable developer mode.
3. Go back to **Settings > System > Developer options** and enable **USB debugging**.
4. Connect your phone to your computer via USB.
5. Accept the prompt on your phone allowing USB debugging from this computer.
6. In Android Studio's top toolbar, click the device dropdown menu and select your connected phone.
7. Click the green **Run** button to install and launch the app.
