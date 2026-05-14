# Universal Remote Control

A modern Android application designed to control various smart TV brands and devices over the local network. This project focuses on providing a unified and intuitive user experience for device discovery and remote interaction.

## Features

- **Device Discovery**: Automatically scans the local network for compatible devices using mDNS/DNS-SD (NSD on Android).
- **Multi-Brand Support**:
    - **Android TV**: Full support for Android TV Remote v2 protocol, including secure pairing and command channel.
    - **Samsung Tizen**: Basic integration for Samsung Smart TVs.
    - **Extensible Architecture**: Designed to easily add support for LG webOS, Roku, and Generic DLNA devices.
- **Unified UI**: A clean, Material 3-based interface with a dynamic keyboard and brand-specific color coding.
- **Voice Search**: Integrated voice recognition to send text directly to your TV.
- **Secure Connection**: Uses SSL/TLS with custom certificate management for secure communication with Android TVs.

## Architecture

The project follows modern Android development practices:
- **Language**: 100% Kotlin.
- **UI Framework**: Jetpack Compose with Material 3.
- **Asynchronous Programming**: Kotlin Coroutines and Flow.
- **Protocol Management**: Google Protobuf for efficient data serialization.
- **Security**: custom `KeyStoreManager` for RSA key pair and certificate generation.

## Technical Details

### Android TV Protocol
The app implements the official Google "Polo" pairing protocol and the Remote Control v2 protocol. It handles the specific handshake sequence:
1. TLS Handshake.
2. Configuration exchange.
3. Session activation.
4. Heartbeat (Ping/Pong) for connection maintenance.

## Getting Started

1. Clone the repository.
2. Ensure you have the latest Android Studio installed.
3. Connect your Android phone and Smart TV to the same Wi-Fi network.
4. Build and run the app.
5. Select your device from the list and follow the pairing instructions on your TV.

## License

This project is licensed under the MIT License.
