# ELI8 Development Logbook
**BIT/2025/66882 - Mobile App Development**

This document tracks the evolution, architectural decisions, and feature milestones of the ELI8 RSS Reader project.

---

## 📅 Project Status: Active Development
**Current Version:** v1.0.0-alpha  
**Primary Focus:** Modularization, Social Engagement, and Discovery.

---

## 🏗 Architectural Overview

### 1. State Management
- **Navigation:** Custom stack-based navigation using `mutableStateListOf`. This allows for fine-grained control over transitions and deep-linking without the overhead of heavy navigation libraries.
- **UI State:** Leverages Compose's `remember` and `derivedStateOf` to ensure high performance during complex list rendering and filtering.

### 2. Data Layer
- **Local Persistence:** Room DB (`AppDatabase.kt`) caches `RSSItemEntity` and serialized `Comment` threads.
- **Cloud Sync:** Firebase Firestore acts as the primary source of truth, with real-time listeners for content updates and user interactions (votes/follows).
- **Serialization:** Gson is used to handle nested recursive data structures within the SQLite local storage.

---

## 🚀 Feature Milestone Log

### **Phase 1: Foundation & UI (v0.1 - v0.5)**
- [x] Initial app structure with Jetpack Compose.
- [x] Basic RSS feed fetching from Firestore.
- [x] Integrated Media3 ExoPlayer for video support.
- [x] Implementation of `ModernSocialCard` with dynamic aspect ratio handling to prevent content cropping.

### **Phase 2: Social & Engagement (v0.6 - v0.8)**
- [x] **Recursive Comments:** Implementation of Reddit-style nested threads using recursive data models and hierarchical UI rendering.
- [x] **Voting System:** Firestore-backed Like/Dislike system for both articles and comments.
- [x] **Business Accounts:** Logic for business roles, following specific sources, and specialized branding (pink FAB).

### **Phase 3: Optimization & Personalization (v0.9 - v1.0)**
- [x] **Discovery Algorithm:** Implemented a weighted ranking system that boosts content based on user vote history and category preference.
- [x] **Search Engine:** Keyword-based search across titles, descriptions, and authors with persistent search history.
- [x] **Offline Resilience:** Enhanced Room DB to store full comment threads, enabling a complete offline reading experience.
- [x] **UI/UX Polish:** Refactored MainActivity into modular composables, implemented custom splash screen animations, and centralized settings.

---

## 🔧 Technical Decisions & Learnings

### **1. Performance over Convenience**
- **Decision:** Used `remember(searchQuery)` for filtering instead of global state.
- **Why:** Prevented expensive list operations during scroll animations, maintaining a steady 60-120fps on physical devices.

### **2. Recursive UI Rendering**
- **Decision:** Implemented a custom flattening algorithm for comments before rendering.
- **Why:** Avoided deep nesting of Composables which can cause stack overflow and performance degradation; instead, rendered a flat list with dynamic indentation.

### **3. Privacy & Session Management**
- **Decision:** Implemented a unique `user_id` stored in SharedPreferences rather than requiring mandatory account creation initially.
- **Why:** Lowered the barrier to entry while still allowing for personalized discovery.

---

## 🔮 Future Roadmap
- [ ] Implement Push Notifications for followed business updates.
- [ ] Add "Dark Mode" specific vector assets for brand consistency.
- [ ] Migrate navigation to a typed Navigation Component if complexity grows beyond 10+ screens.
- [ ] Implement an "Explainer" mode (ELI5 toggle) to simplify complex article text using AI.

---
*Last updated: $(date +'%Y-%m-%d')*
