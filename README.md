# ELI8 - RSS Reader

ELI8 is a modern, social-first RSS and content reader built with **Jetpack Compose**. It features a personalized discovery algorithm, recursive Reddit-style comments, and full offline support with local caching.

## 🚀 Key Features

- **Personalized Feed**: A smart ranking algorithm that prioritizes content based on your likes, dislikes, and preferred categories.
- **Recursive Nested Comments**: Full Reddit-style nested comment threads with support for deep replies and community voting.
- **Search System**: Instantly find articles, sources, or creators with a comprehensive keyword search.
- **Multi-Media Support**: Seamless integration for high-quality images and a built-in video player using **Media3 ExoPlayer**.
- **Offline Mode**: Powered by **Room DB**, the app caches articles and comments locally, ensuring a smooth experience even without an internet connection.
- **Business Accounts**: Specialized roles for businesses to post content and for users to follow their favorite sources.
- **Theme Customization**: Support for Light, Dark, and System theme preferences.

## 🛠 Tech Stack

- **UI**: Jetpack Compose (Material 3)
- **Database**: Room (Local Caching) & Firebase Firestore (Cloud Sync)
- **Authentication**: Firebase Auth (Email Verification)
- **Image Loading**: Coil
- **Video**: Media3 ExoPlayer
- **Networking**: Gson for JSON serialization

## 📸 Screenshots

*(Add your screenshots here to make your repo shine!)*

## 📥 Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/Atypicaljames/ELI8-RSS-Reader.git
   ```
2. Open the project in **Android Studio Ladybug** or newer.
3. Add your `google-services.json` to the `app/` directory.
4. Build and run on your device.

---
Built with ❤️ by Atypicaljames
