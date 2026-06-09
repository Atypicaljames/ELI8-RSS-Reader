package com.example.rssreader

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.room.Room
import com.example.rssreader.ui.theme.ELI8Theme
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        setupSplashScreen(splashScreen)
        enableEdgeToEdge()

        val db = FirebaseFirestore.getInstance()
        val auth = FirebaseAuth.getInstance()
        val localDb = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java, "eli8-db"
            ).fallbackToDestructiveMigration(false).build()
        val rssDao = localDb.rssItemDao()

        setContent {
            ELI8App(db, auth, rssDao)
        }
    }

    private fun setupSplashScreen(splashScreen: androidx.core.splashscreen.SplashScreen) {
        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val slideUp = android.view.animation.TranslateAnimation(
                0f, 0f, 0f, -splashScreenView.view.height.toFloat()
            ).apply {
                duration = 600L
                interpolator = android.view.animation.AnticipateInterpolator()
                setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                    override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                        splashScreenView.remove()
                    }
                    override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                })
            }
            splashScreenView.view.startAnimation(slideUp)
        }
    }

    @Composable
    fun ELI8App(db: FirebaseFirestore, auth: FirebaseAuth, rssDao: RSSItemDao) {
        val rssItems = remember { mutableStateListOf<RSSItem>() }
        val context = LocalContext.current
        val sharedPrefs = remember { context.getSharedPreferences("user_prefs", MODE_PRIVATE) }

        var themePreference by remember {
            mutableStateOf(
                try {
                    ThemePreference.valueOf(
                        sharedPrefs.getString("theme_pref", ThemePreference.SYSTEM.name) ?: ThemePreference.SYSTEM.name,
                    )
                } catch (_: Exception) {
                    ThemePreference.SYSTEM
                },
            )
        }

        LaunchedEffect(Unit) {
            db.collection("rss_items_v6").addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null) {
                    val items = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(RSSItem::class.java)?.copy(id = doc.id)
                    }
                    
                    lifecycleScope.launch {
                        rssDao.syncItems(items.map { it.toEntity() })
                    }
                    if (items.isEmpty()) seedDatabase(db)
                }
            }

            rssDao.getAll().collect { entities ->
                rssItems.clear()
                rssItems.addAll(entities.map { it.toModel() })
            }
        }

        ELI8Theme(themePreference = themePreference) {
            val navigationStack = remember { mutableStateListOf("home") }
            val currentScreen = navigationStack.last()
            
            var selectedItem by remember { mutableStateOf<RSSItem?>(null) }
            var itemToEdit by remember { mutableStateOf<RSSItem?>(null) }
            var selectedTab by remember { mutableIntStateOf(0) }
            var savedName by remember { mutableStateOf("") }
            var isAdmin by remember { mutableStateOf(value = false) }
            var isBusinessAccount by remember { mutableStateOf(value = false) }
            val userVotes = remember { mutableStateMapOf<String, Int>() }
            val followedBusinesses = remember { mutableStateListOf<String>() }

            var userId by remember { 
                mutableStateOf(
                    sharedPrefs.getString("user_id", null) ?: run {
                        val newId = UUID.randomUUID().toString()
                        sharedPrefs.edit { putString("user_id", newId) }
                        newId
                    }
                )
            }

            val navigateTo: (String) -> Unit = { screen ->
                if (navigationStack.last() != screen) {
                    navigationStack.add(screen)
                }
            }

            val navigateBack: () -> Unit = {
                if (selectedItem != null) {
                    selectedItem = null
                } else if (navigationStack.size > 1) {
                    navigationStack.removeAt(navigationStack.size - 1)
                }
            }

            val onLogout: () -> Unit = {
                sharedPrefs.edit { remove("user_id") }
                val newId = UUID.randomUUID().toString()
                sharedPrefs.edit { putString("user_id", newId) }
                userId = newId
                navigationStack.clear()
                navigationStack.add("home")
                selectedTab = 0
            }

            BackHandler(enabled = selectedItem != null || navigationStack.size > 1) {
                navigateBack()
            }

            LaunchedEffect(userId) {
                userVotes.clear()
                followedBusinesses.clear()
                db.collection("users").document(userId).addSnapshotListener { snapshot, _ ->
                    savedName = snapshot?.getString("name") ?: ""
                    isAdmin = snapshot?.getBoolean("isAdmin") ?: false
                    isBusinessAccount = snapshot?.getBoolean("isBusinessAccount") ?: false

                    val votes = snapshot?.get("votes") as? Map<*, *>
                    votes?.forEach { (itemId, vote) ->
                        if ((itemId is String) && (vote is Long)) {
                            sharedPrefs.edit { putInt("vote_$itemId", vote.toInt()) }
                            userVotes[itemId] = vote.toInt()
                        }
                    }
                    
                    val commentVotes = snapshot?.get("comment_votes") as? Map<*, *>
                    commentVotes?.forEach { (commentId, vote) ->
                        if ((commentId is String) && (vote is Long)) {
                            sharedPrefs.edit { putInt("vote_comm_$commentId", vote.toInt()) }
                        }
                    }

                    val followed = snapshot?.get("followed_businesses") as? List<*>
                    followedBusinesses.clear()
                    followed?.filterIsInstance<String>()?.let { followedBusinesses.addAll(it) }
                }
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopBar(
                        selectedItem = selectedItem,
                        currentScreen = currentScreen,
                        followedBusinesses = followedBusinesses,
                        onBack = navigateBack,
                        onSettings = { navigateTo("settings") },
                        onSearch = { navigateTo("search") }
                    )
                },
                bottomBar = {
                    BottomBar(
                        selectedItem = selectedItem,
                        currentScreen = currentScreen,
                        selectedTab = selectedTab,
                        canPost = isAdmin || isBusinessAccount,
                        onTabSelect = { selectedTab = it },
                        onPost = { 
                            itemToEdit = null
                            navigateTo("admin_upload") 
                        }
                    )
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    MainContent(
                        selectedItem = selectedItem,
                        currentScreen = currentScreen,
                        selectedTab = selectedTab,
                        rssItems = rssItems,
                        userVotes = userVotes,
                        isAdmin = isAdmin,
                        userId = userId,
                        savedName = savedName,
                        followedBusinesses = followedBusinesses,
                        itemToEdit = itemToEdit,
                        db = db,
                        auth = auth,
                        sharedPrefs = sharedPrefs,
                        onSelectItem = { selectedItem = it },
                        onEditItem = { 
                            itemToEdit = it
                            selectedItem = null
                            navigateTo("admin_upload")
                        },
                        onNavigateBack = navigateBack,
                        onNavigateTo = navigateTo,
                        onUpdateTheme = { 
                            themePreference = it 
                            sharedPrefs.edit { putString("theme_pref", it.name) }
                        },
                        onLogout = onLogout
                    )
                }
            }
        }
    }

    @Composable
    fun TopBar(
        selectedItem: RSSItem?,
        currentScreen: String,
        followedBusinesses: List<String>,
        onBack: () -> Unit,
        onSettings: () -> Unit,
        onSearch: () -> Unit
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            AnimatedContent(
                targetState = selectedItem != null || currentScreen != "home",
                transitionSpec = { 
                    (fadeIn(animationSpec = tween(400)) + scaleIn(initialScale = 0.8f))
                        .togetherWith(fadeOut(animationSpec = tween(400)) + scaleOut(targetScale = 0.8f))
                },
                label = "TopBarTransition"
            ) { isBackVisible ->
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isBackVisible) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                        
                        Text(
                            text = when {
                                selectedItem != null -> "Article"
                                currentScreen == "settings" -> "Settings"
                                currentScreen == "watch_history" -> "History"
                                currentScreen == "search_history" -> "Searches"
                                currentScreen == "search" -> "Search"
                                currentScreen == "terms" -> "Terms"
                                currentScreen == "about" -> "About"
                                else -> "ELI8"
                            }, 
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive,
                                fontSize = 32.sp
                            ),
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFF0266),
                            letterSpacing = (-1).sp,
                            modifier = Modifier.animateContentSize()
                        )
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        if (selectedItem == null && currentScreen == "home") {
                            IconButton(onClick = onSearch) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            IconButton(onClick = onSettings) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    if (!isBackVisible && currentScreen == "home" && followedBusinesses.isNotEmpty()) {
                        FollowingRow(followedBusinesses)
                    }
                }
            }
        }
    }

    @Composable
    fun FollowingRow(followedBusinesses: List<String>) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                Text(
                    "Following:", 
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
            }
            items(followedBusinesses) { business ->
                Surface(
                    color = Color(0xFFFF0266).copy(alpha = 0.1f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF0266).copy(alpha = 0.2f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0266))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = business,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFFF0266)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun BottomBar(
        selectedItem: RSSItem?,
        currentScreen: String,
        selectedTab: Int,
        canPost: Boolean,
        onTabSelect: (Int) -> Unit,
        onPost: () -> Unit
    ) {
        AnimatedVisibility(
            visible = selectedItem == null && currentScreen == "home",
            enter = slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn(),
            exit = slideOutVertically(animationSpec = spring(stiffness = Spring.StiffnessLow)) { it } + fadeOut()
        ) {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelect(0) },
                    icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                    label = { Text("Feed") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelect(1) },
                    icon = { Icon(Icons.Default.Bolt, contentDescription = null) },
                    label = { Text("Trending") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabSelect(2) },
                    icon = { Icon(Icons.Default.Storefront, contentDescription = null) },
                    label = { Text("Business") }
                )
                if (canPost) {
                    NavigationBarItem(
                        selected = false,
                        onClick = onPost,
                        icon = { Icon(Icons.Default.AddBox, contentDescription = null) },
                        label = { Text("Post") }
                    )
                }
            }
        }
    }

    @Composable
    fun MainContent(
        selectedItem: RSSItem?,
        currentScreen: String,
        selectedTab: Int,
        rssItems: List<RSSItem>,
        userVotes: Map<String, Int>,
        isAdmin: Boolean,
        userId: String,
        savedName: String,
        followedBusinesses: List<String>,
        itemToEdit: RSSItem?,
        db: FirebaseFirestore,
        auth: FirebaseAuth,
        sharedPrefs: android.content.SharedPreferences,
        onSelectItem: (RSSItem?) -> Unit,
        onEditItem: (RSSItem) -> Unit,
        onNavigateBack: () -> Unit,
        onNavigateTo: (String) -> Unit,
        onUpdateTheme: (ThemePreference) -> Unit,
        onLogout: () -> Unit
    ) {
        val context = LocalContext.current
        AnimatedContent(
            targetState = selectedItem ?: (currentScreen + if (currentScreen == "home") "_$selectedTab" else ""),
            transitionSpec = {
                if (targetState is RSSItem || initialState is RSSItem) {
                    (slideInVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) { height -> height } + fadeIn())
                        .togetherWith(slideOutVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)) { height -> height } + fadeOut())
                } else {
                    (fadeIn(animationSpec = tween(500)) + scaleIn(initialScale = 0.85f, animationSpec = tween(500)))
                        .togetherWith(fadeOut(animationSpec = tween(500)) + scaleOut(targetScale = 0.85f, animationSpec = tween(500)))
                }
            },
            label = "MainContentTransition"
        ) { target ->
            when {
                target is RSSItem -> {
                    DetailScreen(
                        item = target,
                        isAdmin = isAdmin,
                        onDelete = {
                            db.collection("rss_items_v6").document(target.id).delete()
                                .addOnSuccessListener {
                                    Toast.makeText(context, "Post deleted", Toast.LENGTH_SHORT).show()
                                }
                            onNavigateBack()
                        },
                        onEdit = { onEditItem(target) },
                        onLike = { handleVote(target, 1, db, sharedPrefs, userId, onSelectItem) },
                        onDislike = { handleVote(target, -1, db, sharedPrefs, userId, onSelectItem) },
                        onComment = { commentText, parentId ->
                            postComment(target, commentText, savedName, parentId, db, onSelectItem, context)
                        },
                        onLikeComment = { commentId ->
                            handleCommentVote(target, commentId, 1, db, sharedPrefs, userId, onSelectItem)
                        },
                        onDislikeComment = { commentId ->
                            handleCommentVote(target, commentId, -1, db, sharedPrefs, userId, onSelectItem)
                        }
                    )
                }
                target.toString().startsWith("home") -> {
                    HomeContent(
                        rssItems = rankItems(rssItems, selectedTab, userVotes), 
                        userName = savedName, 
                        followedBusinesses = followedBusinesses,
                        onItemClick = { 
                            onSelectItem(it) 
                            saveWatchHistory(it, sharedPrefs)
                        },
                        onFollowBusiness = { business ->
                            toggleFollowBusiness(business, followedBusinesses, db, userId)
                        }
                    )
                }
                target == "settings" -> {
                    SettingsScreen(
                        currentTheme = ThemePreference.valueOf(sharedPrefs.getString("theme_pref", ThemePreference.SYSTEM.name)!!),
                        onThemeChange = onUpdateTheme,
                        onNavigate = onNavigateTo,
                        onLogout = onLogout
                    )
                }
                target == "search" -> {
                    SearchScreen(
                        rssItems = rssItems,
                        onItemClick = { 
                            onSelectItem(it) 
                            saveWatchHistory(it, sharedPrefs)
                        },
                        onSearch = { saveSearchHistory(it, sharedPrefs) }
                    )
                }
                target == "watch_history" -> WatchHistoryScreen(rssItems)
                target == "search_history" -> SearchHistoryScreen()
                target == "terms" -> InfoScreen("Terms", "...")
                target == "about" -> InfoScreen("About", "...")
                target == "admin_upload" -> {
                    AdminUploadScreen(
                        editingItem = itemToEdit,
                        onUpload = { newItem ->
                            uploadPost(newItem, db, context)
                            onNavigateBack()
                        },
                        onCancel = onNavigateBack
                    )
                }
                else -> {
                    LoginScreen(
                        auth = auth,
                        isAdmin = isAdmin,
                        userId = userId,
                        onSave = { name, email, profilePic, isBusiness ->
                            saveUser(name, email, userId, profilePic, isBusiness, db, context)
                            onNavigateBack()
                        }
                    )
                }
            }
        }
    }

    private fun RSSItem.toEntity() = RSSItemEntity(
        id = id, title = title, text = text, type = type.name,
        media = media, description = description, url = url,
        likes = likes, dislikes = dislikes,
        comments = com.google.gson.Gson().toJson(comments)
    )

    private fun RSSItemEntity.toModel() = RSSItem(
        id = id, title = title, text = text,
        type = try { RSSType.valueOf(type) } catch(_: Exception) { RSSType.TEXT },
        media = media, description = description, url = url,
        likes = likes, dislikes = dislikes,
        comments = try {
            val listType = object : com.google.gson.reflect.TypeToken<List<Comment>>() {}.type
            com.google.gson.Gson().fromJson(comments, listType) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    )

    private fun handleVote(item: RSSItem, voteValue: Int, db: FirebaseFirestore, sharedPrefs: android.content.SharedPreferences, userId: String, onUpdate: (RSSItem) -> Unit) {
        val currentVote = sharedPrefs.getInt("vote_${item.id}", 0)
        if (currentVote == voteValue) return

        val likesInc = if (voteValue == 1) 1 else if (currentVote == 1) -1 else 0
        val dislikesInc = if (voteValue == -1) 1 else if (currentVote == -1) -1 else 0

        db.collection("rss_items_v6").document(item.id).update(
            "likes", FieldValue.increment(likesInc.toLong()),
            "dislikes", FieldValue.increment(dislikesInc.toLong())
        ).addOnSuccessListener {
            sharedPrefs.edit { putInt("vote_${item.id}", voteValue) }
            db.collection("users").document(userId).set(mapOf("votes" to mapOf(item.id to voteValue)), SetOptions.merge())
            onUpdate(item.copy(likes = item.likes + likesInc, dislikes = item.dislikes + dislikesInc))
        }
    }

    private fun postComment(item: RSSItem, text: String, userName: String, parentId: String?, db: FirebaseFirestore, onUpdate: (RSSItem) -> Unit, context: android.content.Context) {
        val newComment = Comment(id = UUID.randomUUID().toString(), text = text, userName = userName.ifBlank { "Anonymous" })
        
        val newComments = if (parentId == null) {
            item.comments + newComment
        } else {
            fun addReplyRecursive(comments: List<Comment>, targetId: String, reply: Comment): List<Comment> {
                return comments.map {
                    if (it.id == targetId) {
                        it.copy(replies = it.replies + reply)
                    } else {
                        it.copy(replies = addReplyRecursive(it.replies, targetId, reply))
                    }
                }
            }
            addReplyRecursive(item.comments, parentId, newComment)
        }

        db.collection("rss_items_v6").document(item.id).update("comments", newComments).addOnSuccessListener {
            onUpdate(item.copy(comments = newComments))
            Toast.makeText(context, "Comment posted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCommentVote(item: RSSItem, commentId: String, voteValue: Int, db: FirebaseFirestore, sharedPrefs: android.content.SharedPreferences, userId: String, onUpdate: (RSSItem) -> Unit) {
        val currentVote = sharedPrefs.getInt("vote_comm_$commentId", 0)
        if (currentVote == voteValue) return

        val likesInc = if (voteValue == 1) 1 else if (currentVote == 1) -1 else 0
        val dislikesInc = if (voteValue == -1) 1 else if (currentVote == -1) -1 else 0

        fun updateVoteRecursive(comments: List<Comment>, targetId: String): List<Comment> {
            return comments.map {
                if (it.id == targetId) {
                    it.copy(likes = it.likes + likesInc, dislikes = it.dislikes + dislikesInc)
                } else {
                    it.copy(replies = updateVoteRecursive(it.replies, targetId))
                }
            }
        }
        val newComments = updateVoteRecursive(item.comments, commentId)

        db.collection("rss_items_v6").document(item.id).update("comments", newComments).addOnSuccessListener {
            sharedPrefs.edit { putInt("vote_comm_$commentId", voteValue) }
            db.collection("users").document(userId).set(mapOf("comment_votes" to mapOf(commentId to voteValue)), SetOptions.merge())
            onUpdate(item.copy(comments = newComments))
        }
    }

    private fun rankItems(items: List<RSSItem>, tab: Int, userVotes: Map<String, Int>): List<RSSItem> {
        return when (tab) {
            1 -> items.sortedByDescending { (it.likes + it.dislikes) * 1.5 + (it.likes - it.dislikes) }
            2 -> items.filter { it.type == RSSType.AD }
            else -> {
                val prefs = mutableMapOf<RSSType, Int>()
                val itemMap = items.associateBy { it.id }
                userVotes.forEach { (id, v) -> itemMap[id]?.let { prefs[it.type] = (prefs[it.type] ?: 0) + v } }
                
                items.sortedByDescending { item ->
                    val voteBonus = when (userVotes[item.id]) {
                        1 -> 100.0
                        -1 -> -200.0
                        else -> 0.0
                    }
                    (item.likes - item.dislikes) + (prefs[item.type] ?: 0) * 10.0 + voteBonus
                }
            }
        }
    }

    private fun saveWatchHistory(item: RSSItem, sharedPrefs: android.content.SharedPreferences) {
        val history = sharedPrefs.getStringSet("watch_history", emptySet())?.toMutableSet() ?: mutableSetOf()
        history.add("${item.id}|${System.currentTimeMillis()}")
        sharedPrefs.edit { putStringSet("watch_history", history) }
    }

    private fun saveSearchHistory(query: String, sharedPrefs: android.content.SharedPreferences) {
        if (query.isBlank()) return
        val history = sharedPrefs.getStringSet("search_history", emptySet())?.toMutableSet() ?: mutableSetOf()
        history.add("$query|${System.currentTimeMillis()}")
        sharedPrefs.edit { putStringSet("search_history", history) }
    }

    private fun toggleFollowBusiness(business: String, current: List<String>, db: FirebaseFirestore, userId: String) {
        val newList = if (current.contains(business)) current - business else current + business
        db.collection("users").document(userId).update("followed_businesses", newList)
    }

    private fun uploadPost(item: RSSItem, db: FirebaseFirestore, context: android.content.Context) {
        val task = if (item.id.isBlank()) db.collection("rss_items_v6").add(item) else db.collection("rss_items_v6").document(item.id).set(item)
        task.addOnSuccessListener { Toast.makeText(context, "Success!", Toast.LENGTH_SHORT).show() }
    }

    private fun saveUser(name: String, email: String, userId: String, profilePic: String?, isBusiness: Boolean, db: FirebaseFirestore, context: android.content.Context) {
        if (name.isBlank()) return
        val userData = mutableMapOf(
            "name" to name,
            "email" to email,
            "updatedAt" to FieldValue.serverTimestamp(),
            "profilePicture" to profilePic,
            "isBusinessAccount" to isBusiness,
            "isAdmin" to (email.contains("admin", ignoreCase = true)) // Simple separation logic
        )
        db.collection("users").document(userId).set(userData, SetOptions.merge())
            .addOnSuccessListener { Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show() }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun seedDatabase(db: FirebaseFirestore) {
        val seedTrace = FirebasePerformance.getInstance().newTrace("seed_database")
        seedTrace.start()
        seedTrace.stop()
    }
}

@Composable
fun HomeContent(
    rssItems: List<RSSItem>,
    userName: String,
    followedBusinesses: List<String>,
    onItemClick: (RSSItem) -> Unit,
    onFollowBusiness: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    val filteredItems = remember(rssItems, searchQuery) {
        rssItems.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.text.contains(searchQuery, ignoreCase = true)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (userName.isNotEmpty()) {
                var greetingVisible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { greetingVisible = true }
                
                AnimatedVisibility(
                    visible = greetingVisible,
                    enter = slideInVertically { -it } + fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hi, $userName! 👋",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            var trendingVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { trendingVisible = true }

            AnimatedVisibility(
                visible = trendingVisible && followedBusinesses.isNotEmpty(),
                enter = slideInHorizontally { -it } + fadeIn(),
                exit = slideOutHorizontally { -it } + fadeOut()
            ) {
                val followedContent = rssItems.filter { followedBusinesses.contains(it.text) }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(followedContent.take(12)) { item ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(
                                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                                            colors = listOf(Color(0xFFFF0266), Color(0xFF6200EE))
                                        )
                                    )
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .clickable { onItemClick(item) }
                            ) {
                                if (item.media != null) {
                                    AsyncImage(
                                        model = item.media,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(32.dp),
                                        tint = Color(0xFFFF0266)
                                    )
                                }
                            }
                            Text(
                                text = item.text.take(8),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.3f))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }
                    
                    AnimatedVisibility(
                        visible = visible,
                        enter = slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(),
                        modifier = Modifier.animateContentSize()
                    ) {
                        ModernSocialCard(
                            rssItem = item, 
                            isFollowed = followedBusinesses.contains(item.text),
                            onClick = { onItemClick(item) },
                            onFollow = { onFollowBusiness(item.text) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    currentTheme: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        Text("App Theme", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ThemePreference.entries.forEach { theme ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = currentTheme == theme,
                        onClick = { onThemeChange(theme) }
                    )
                    Text(theme.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        SettingsItem("Edit Profile", Icons.Default.AccountCircle) { onNavigate("login") }
        SettingsItem("Watch History", Icons.Default.History) { onNavigate("watch_history") }
        SettingsItem("Search History", Icons.Default.Search) { onNavigate("search_history") }
        SettingsItem("Terms and Conditions", Icons.Default.Description) { onNavigate("terms") }
        SettingsItem("About ELI8", Icons.Default.Info) { onNavigate("about") }

        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Logout", fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingsItem(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
fun WatchHistoryScreen(allRssItems: List<RSSItem>) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE) }
    val historySet = sharedPrefs.getStringSet("watch_history", emptySet()) ?: emptySet()

    val historyItems = historySet.asSequence().mapNotNull { entry ->
        val parts = entry.split("|")
        if (parts.size == 2) {
            val itemId = parts[0]
            val timestamp = parts[1].toLongOrNull() ?: 0L
            allRssItems.find { it.id == itemId }?.let { it to timestamp }
        } else null
    }.sortedByDescending { it.second }.toList()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Watch History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (historyItems.isEmpty()) {
            item { Text("No history found.") }
        } else {
            items(historyItems) { (item, timestamp) ->
                val date = Date(timestamp)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(item.title, fontWeight = FontWeight.Bold)
                        Text(date.toString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchHistoryScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE) }
    val historySet = sharedPrefs.getStringSet("search_history", emptySet()) ?: emptySet()

    val searchItems = historySet.asSequence().mapNotNull { entry ->
        val parts = entry.split("|")
        if (parts.size == 2) {
            parts[0] to (parts[1].toLongOrNull() ?: 0L)
        } else null
    }.sortedByDescending { it.second }.toList()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Search History", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
        }
        if (searchItems.isEmpty()) {
            item { Text("No searches yet.") }
        } else {
            items(searchItems) { (query, _) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(query)
                }
            }
        }
    }
}

@Composable
fun InfoScreen(title: String, content: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))
        Text(content, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
    }
}

@Composable
fun LoginScreen(auth: FirebaseAuth, isAdmin: Boolean, userId: String, onSave: (String, String, String?, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var profilePic by remember { mutableStateOf<String?>(null) }
    var isBusiness by remember { mutableStateOf(false) }
    var verificationCode by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(value = false) }
    var generatedCode by remember { mutableStateOf("") }

    val context = LocalContext.current
    
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { profilePic = it.toString() }
    }

    LaunchedEffect(userId) {
        FirebaseFirestore.getInstance().collection("users").document(userId).get()
            .addOnSuccessListener { snapshot ->
                name = snapshot.getString("name") ?: ""
                email = snapshot.getString("email") ?: ""
                profilePic = snapshot.getString("profilePicture")
                isBusiness = snapshot.getBoolean("isBusinessAccount") ?: false
            }
    }

    val isEmailValid = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    val canRequestCode = name.isNotBlank() && email.isNotBlank() && isEmailValid
    val canVerify = verificationCode.length == 6

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (isVerifying) "Verify Email" else "Profile Settings",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        if (!isVerifying) {
            // Profile Picture Selection
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { 
                        photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                if (profilePic != null) {
                    AsyncImage(
                        model = profilePic,
                        contentDescription = "Profile Picture",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        "Edit",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = name.isBlank() && name.isNotEmpty()
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = email.isNotEmpty() && !isEmailValid,
                supportingText = {
                    if (email.isNotEmpty() && !isEmailValid) {
                        Text("Please enter a valid email address")
                    }
                }
            )

            if (isAdmin) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Business Account",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Enable business features and analytics",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = isBusiness,
                            onCheckedChange = { isBusiness = it }
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = verificationCode,
                onValueChange = { if (it.length <= 6) verificationCode = it },
                label = { Text("Verification Code") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                )
            )

            TextButton(onClick = { isVerifying = false }) {
                Text("Change Email")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (!isVerifying) {
                    val code = (100000..999999).random().toString()
                    generatedCode = code
                    isVerifying = true

                    val actionCodeSettings = ActionCodeSettings.newBuilder()
                        .setUrl("https://eli8-499e8.firebaseapp.com")
                        .setHandleCodeInApp(true)
                        .setAndroidPackageName("com.example.rssreader", true, "1")
                        .build()

                    auth.sendSignInLinkToEmail(email, actionCodeSettings)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                Toast.makeText(context, "Verification email sent to $email", Toast.LENGTH_SHORT).show()
                            } else {
                                Log.e("Auth", "Error sending email", task.exception)
                                Toast.makeText(context, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                            }
                        }

                    Toast.makeText(context, "Demo Mode: Check the toast for your code: $code", Toast.LENGTH_LONG).show()
                } else {
                    if (verificationCode == generatedCode) {
                        onSave(name, email, profilePic, isBusiness)
                    } else {
                        Toast.makeText(context, "Invalid code. Please try again.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = if (isVerifying) canVerify else canRequestCode
        ) {
            Text(
                if (isVerifying) "Verify & Finish" else "Send Verification Code",
                fontWeight = FontWeight.Bold
            )
        }

        if (!isVerifying) {
            TextButton(
                onClick = { onSave("", "", null, false) },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Skip", color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
fun AdminUploadScreen(
    editingItem: RSSItem? = null,
    onUpload: (RSSItem) -> Unit,
    onCancel: () -> Unit
) {
    var title by remember(editingItem) { mutableStateOf(editingItem?.title ?: "") }
    var source by remember(editingItem) { mutableStateOf(editingItem?.text ?: "") }
    var description by remember(editingItem) { mutableStateOf(editingItem?.description ?: "") }
    var type by remember(editingItem) { mutableStateOf(editingItem?.type ?: RSSType.TEXT) }
    var mediaUrl by remember(editingItem) { mutableStateOf(editingItem?.media ?: "") }
    var url by remember(editingItem) { mutableStateOf(editingItem?.url ?: "") }

    val context = LocalContext.current
    
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val fileName = "post_media_${System.currentTimeMillis()}"
                val file = java.io.File(context.cacheDir, fileName)
                inputStream?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                mediaUrl = file.absolutePath
            } catch (_: Exception) {
                mediaUrl = it.toString()
            }
            type = if (it.toString().contains("video", ignoreCase = true)) RSSType.VIDEO else RSSType.IMAGE
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = if (editingItem == null) "Create New Post" else "Edit Post",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = Color(0xFFFF0266)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Post Preview",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        ModernSocialCard(
            rssItem = RSSItem(
                title = title,
                text = source,
                description = description,
                type = type,
                media = mediaUrl.ifBlank { null }
            ),
            onClick = {}
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (mediaUrl.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.05f))
            ) {
                AsyncImage(
                    model = mediaUrl,
                    contentDescription = "Preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                IconButton(
                    onClick = { mediaUrl = "" },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = {
                photoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Icon(Icons.Default.CloudUpload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select Media from Phone", color = MaterialTheme.colorScheme.onSecondaryContainer)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Article Title") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            label = { Text("Source / Author") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Content Type", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            RSSType.entries.forEach { rssType ->
                FilterChip(
                    selected = type == rssType,
                    onClick = { type = rssType },
                    label = { Text(rssType.name) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = mediaUrl,
            onValueChange = { mediaUrl = it },
            label = { Text("Media Image/Video URL (or Local Path)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text("https://example.com/image.jpg") }
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Source Link (optional)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Cancel")
            }
            Button(
                onClick = {
                    if (title.isNotBlank() && source.isNotBlank()) {
                        onUpload(
                            (editingItem ?: RSSItem()).copy(
                                title = title,
                                text = source,
                                description = description,
                                type = type,
                                media = mediaUrl.ifBlank { null },
                                url = url.ifBlank { "https://google.com" }
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0266))
            ) {
                Text(if (editingItem == null) "Upload Post" else "Save Changes", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun DetailScreen(
    item: RSSItem,
    isAdmin: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onComment: (String, String?) -> Unit,
    onLikeComment: (String) -> Unit,
    onDislikeComment: (String) -> Unit
) {
    val context = LocalContext.current
    var commentText by remember { mutableStateOf("") }
    var replyingToId by remember { mutableStateOf<String?>(null) }
    var replyingToName by remember { mutableStateOf<String?>(null) }

    var mediaAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    val flattened = remember(item.comments) {
        fun flatten(comments: List<Comment>, level: Int = 0): List<Pair<Comment, Int>> {
            val result = mutableListOf<Pair<Comment, Int>>()
            for (comment in comments) {
                result.add(comment to level)
                result.addAll(flatten(comment.replies, level + 1))
            }
            return result
        }
        flatten(item.comments)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        item {
            if (item.media != null) {
                if (item.type == RSSType.VIDEO) {
                    VideoPlayer(
                        videoUrl = item.media,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(mediaAspectRatio),
                        onRatioChange = { mediaAspectRatio = it }
                    )
                } else {
                    AsyncImage(
                        model = item.media,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(mediaAspectRatio),
                        contentScale = ContentScale.Fit,
                        onSuccess = { state ->
                            val size = state.painter.intrinsicSize
                            if (size.width > 0 && size.height > 0) {
                                mediaAspectRatio = size.width / size.height
                            }
                        }
                    )
                }
            }

            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                if (isAdmin) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onEdit,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit", fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Delete", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val tagColor = when(item.type) {
                        RSSType.TEXT -> Color(0xFF6200EE)
                        RSSType.IMAGE -> Color(0xFF03DAC5)
                        RSSType.VIDEO -> Color(0xFFFF0266)
                        RSSType.AD -> Color(0xFFFFA000)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(tagColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = item.type.name,
                            style = MaterialTheme.typography.labelLarge,
                            color = tagColor,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Black,
                            lineHeight = 36.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Source: ${item.text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val userVote = remember(item.id) {
                            context.getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
                                .getInt("vote_${item.id}", 0)
                        }

                        val likeScale by animateFloatAsState(
                            targetValue = if (userVote == 1) 1.2f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "LikeAnimation"
                        )

                        IconButton(onClick = onLike) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Like",
                                tint = if (userVote == 1) Color.Red else Color.Gray.copy(alpha = 0.5f),
                                modifier = Modifier.graphicsLayer(scaleX = likeScale, scaleY = likeScale)
                            )
                        }
                        
                        AnimatedContent(
                            targetState = item.likes,
                            transitionSpec = {
                                (fadeIn() + scaleIn()).togetherWith(fadeOut())
                            },
                            label = "LikesCountAnimation"
                        ) { likes ->
                            Text(
                                text = likes.toString(), 
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (userVote == 1) Color.Red else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(onClick = onDislike) {
                            Icon(
                                imageVector = Icons.Default.ThumbDown,
                                contentDescription = "Dislike",
                                tint = if (userVote == -1) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f)
                            )
                        }

                        AnimatedContent(
                            targetState = item.dislikes,
                            transitionSpec = {
                                (fadeIn() + scaleIn()).togetherWith(fadeOut())
                            },
                            label = "DislikesCountAnimation"
                        ) { dislikes ->
                            Text(
                                text = dislikes.toString(), 
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (userVote == -1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 24.dp))

                Text(
                    text = item.description.ifEmpty { "No additional description available for this item." },
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 28.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(32.dp))

                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    Button(
                        onClick = {
                            if (item.url.isNotBlank() && item.url.startsWith("http")) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, item.url.toUri())
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Could not open link", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Invalid source URL", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    "View Original Source",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    item.url.ifBlank { "No URL provided" },
                                    style = MaterialTheme.typography.bodySmall,
                                    textDecoration = TextDecoration.Underline,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Text("Comments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(modifier = Modifier.height(16.dp))

                if (replyingToId != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Replying to @$replyingToName",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { 
                                replyingToId = null
                                replyingToName = null
                            }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                Box(modifier = Modifier.padding(horizontal = 24.dp)) {
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (replyingToId != null) "Write a reply..." else "Leave a comment...") },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (commentText.isNotBlank()) {
                                    onComment(commentText, replyingToId)
                                    commentText = ""
                                    replyingToId = null
                                    replyingToName = null
                                }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        shape = if (replyingToId != null) RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp) else RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        items(flattened, key = { it.first.id }) { (comment, level) ->
            RedditCommentItem(
                comment = comment,
                level = level,
                onLike = { onLikeComment(comment.id) },
                onDislike = { onDislikeComment(comment.id) },
                onReply = { 
                    replyingToId = comment.id 
                    replyingToName = comment.userName
                }
            )
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun ModernSocialCard(
    rssItem: RSSItem, 
    isFollowed: Boolean = false,
    onClick: () -> Unit,
    onFollow: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var isMuted by remember { mutableStateOf(true) }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow),
        label = "CardPressScale"
    )

    val rotation by animateFloatAsState(
        targetValue = if (isPressed) -2f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "CardPressRotation"
    )

    var mediaAspectRatio by remember { mutableFloatStateOf(16f / 9f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .graphicsLayer(
                scaleX = scale, 
                scaleY = scale,
                rotationZ = rotation
            )
            .clickable(interactionSource = interactionSource, indication = null) { 
                if (rssItem.type == RSSType.VIDEO) {
                    isMuted = !isMuted
                } else {
                    onClick()
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, end = 12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                border = BorderStroke(1.dp, Color(0xFFFF0266).copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(mediaAspectRatio)
                        .graphicsLayer {
                            val cameraDistance = 8 * density
                            this.cameraDistance = cameraDistance
                            rotationX = if (isPressed) 5f else 0f
                        }
                ) {
                    if (rssItem.media != null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (rssItem.type == RSSType.VIDEO) {
                                VideoPlayer(
                                    videoUrl = rssItem.media,
                                    modifier = Modifier.fillMaxSize(),
                                    isMuted = isMuted,
                                    onRatioChange = { mediaAspectRatio = it }
                                )
                            } else {
                                AsyncImage(
                                    model = rssItem.media,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    onSuccess = { state ->
                                        val size = state.painter.intrinsicSize
                                        if (size.width > 0 && size.height > 0) {
                                            mediaAspectRatio = size.width / size.height
                                        }
                                    },
                                    error = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFFFF0266).copy(alpha = 0.1f))
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFFFF0266).copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = null,
                                tint = Color(0xFFFF0266).copy(alpha = 0.2f),
                                modifier = Modifier.size(64.dp)
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFFFF0266),
                        shape = RoundedCornerShape(bottomStart = 20.dp),
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Text(
                            text = rssItem.type.name,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Black
                        )
                    }

                    if (rssItem.type == RSSType.VIDEO) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = CircleShape,
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = rssItem.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 28.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "by ${rssItem.text}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFF0266),
                        fontWeight = FontWeight.Bold
                    )

                    if (rssItem.type == RSSType.AD) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isFollowed) "Following" else "Follow",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isFollowed) Color.Gray else Color(0xFFFF0266),
                            fontWeight = FontWeight.Black,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isFollowed) Color.Transparent else Color(0xFFFF0266).copy(alpha = 0.1f))
                                .clickable { onFollow() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(Color.Gray))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = rssItem.likes.toString() + " 🔥",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = rssItem.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(56.dp)
                .offset(x = (0).dp, y = (0).dp)
                .clickable { onClick() },
            shape = CircleShape,
            color = Color(0xFFFF0266),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (rssItem.type == RSSType.VIDEO) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun VideoPlayer(videoUrl: String, modifier: Modifier = Modifier, isMuted: Boolean = false, onRatioChange: (Float) -> Unit = {}) {
    val context = LocalContext.current
    val exoPlayer = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            val uri = if (videoUrl.startsWith("http")) {
                videoUrl.toUri()
            } else {
                android.net.Uri.fromFile(java.io.File(videoUrl))
            }
            setMediaItem(MediaItem.fromUri(uri))
            
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        onRatioChange(videoSize.width.toFloat() / videoSize.height.toFloat())
                    }
                }
            })

            prepare()
            playWhenReady = true
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    LaunchedEffect(isMuted) {
        exoPlayer.volume = if (isMuted) 0f else 1f
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = true
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier
    )
}

@Composable
fun RedditCommentItem(
    comment: Comment,
    level: Int = 0,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onReply: () -> Unit
) {
    val context = LocalContext.current
    val commentVote = remember(comment.id) {
        context.getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("vote_comm_${comment.id}", 0)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max)
            .padding(start = (16 + (level * 16)).dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(
                    if (level > 0) {
                        val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow, Color.Magenta)
                        colors[level % colors.size].copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    }
                )
                .padding(vertical = 4.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF0266).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = comment.userName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFF0266),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = comment.userName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "• 2h",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = comment.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Upvote",
                        tint = if (commentVote == 1) Color(0xFFFF4500) else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Text(
                    text = (comment.likes - comment.dislikes).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (commentVote) {
                        1 -> Color(0xFFFF4500)
                        -1 -> Color(0xFF7193FF)
                        else -> Color.Gray
                    }
                )

                IconButton(onClick = onDislike, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowDownward,
                        contentDescription = "Downvote",
                        tint = if (commentVote == -1) Color(0xFF7193FF) else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                TextButton(onClick = onReply, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reply", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
    
    if (level == 0) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    }
}

@Composable
fun SearchScreen(
    rssItems: List<RSSItem>,
    onItemClick: (RSSItem) -> Unit,
    onSearch: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredItems = remember(rssItems, searchQuery) {
        if (searchQuery.isBlank()) emptyList()
        else rssItems.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true) ||
            it.text.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search articles, sources...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { onSearch(searchQuery) }
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (searchQuery.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Gray.copy(alpha = 0.3f)
                    )
                    Text("Type to start searching", color = Color.Gray)
                }
            }
        } else if (filteredItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results found for \"$searchQuery\"", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filteredItems) { item ->
                    ModernSocialCard(
                        rssItem = item,
                        onClick = { 
                            onItemClick(item)
                            onSearch(searchQuery)
                        }
                    )
                }
            }
        }
    }
}
