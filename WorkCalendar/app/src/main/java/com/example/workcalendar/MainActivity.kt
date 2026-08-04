package com.example.workcalendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.room.*
import androidx.work.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ---- 类型转换器（解决 List<String> 存储） ----
class Converters {
    @TypeConverter
    fun fromString(value: String?): List<String>? {
        return Gson().fromJson(value, object : TypeToken<List<String>>() {}.type)
    }
    @TypeConverter
    fun fromList(list: List<String>?): String? {
        return Gson().toJson(list)
    }
}

// ---- 数据实体 ----
@Entity(tableName = "users")
data class UserEntity(@PrimaryKey val phone: String, val name: String)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupName: String,
    val memberPhones: List<String>
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dateTime: Long,
    val remindMinutes: Int = 10,
    val isLeaderTask: Boolean = false,
    val creatorPhone: String
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val senderPhone: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ---- DAO ----
@Dao
interface UserDao {
    @Insert suspend fun insertUser(u: UserEntity)
    @Query("SELECT * FROM users") suspend fun getAll(): List<UserEntity>
}

@Dao
interface GroupDao {
    @Insert suspend fun insertGroup(g: GroupEntity)
    @Query("SELECT * FROM groups") suspend fun getAll(): List<GroupEntity>
    @Query("SELECT * FROM groups WHERE id = :id") suspend fun getById(id: Long): GroupEntity?
}

@Dao
interface ScheduleDao {
    @Insert suspend fun insertSchedule(s: ScheduleEntity)
    @Query("SELECT * FROM schedules") suspend fun getAll(): List<ScheduleEntity>
}

@Dao
interface MessageDao {
    @Insert suspend fun insertMessage(m: MessageEntity)
    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY timestamp DESC") suspend fun getByGroup(groupId: Long): List<MessageEntity>
}

// ---- 数据库 ----
@Database(
    entities = [UserEntity::class, GroupEntity::class, ScheduleEntity::class, MessageEntity::class],
    version = 1
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun groupDao(): GroupDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(ctx: Context) = INSTANCE ?: synchronized(this) {
            Room.databaseBuilder(ctx, AppDatabase::class.java, "work_db")
                .allowMainThreadQueries()
                .build().also { INSTANCE = it }
        }
    }
}

// ---- 提醒工具 ----
class ReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val title = inputData.getString("title") ?: "提醒"
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, NotificationCompat.Builder(applicationContext, "reminder_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("⏰ 日程提醒: $title")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build())
        return Result.success()
    }
}

fun scheduleReminder(ctx: Context, s: ScheduleEntity) {
    WorkManager.getInstance(ctx).enqueue(
        OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(s.remindMinutes * 60L, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString("title", s.title).build())
            .build()
    )
}

// ---- 主界面 ----
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel("reminder_channel", "日程提醒", NotificationManager.IMPORTANCE_HIGH).also {
                getSystemService(NotificationManager::class.java).createNotificationChannel(it)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        setContent { MaterialTheme { AppNav() } }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (db.userDao().getAll().isEmpty()) {
                db.userDao().insertUser(UserEntity("13800001111", "张经理"))
                db.userDao().insertUser(UserEntity("13800002222", "李同事"))
                db.groupDao().insertGroup(GroupEntity(groupName = "项目群", memberPhones = listOf("13800001111","13800002222")))
            }
        }
    }

    NavHost(nav, "main") {
        composable("main") { MainScreen(nav) }
        composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            ChatScreen(it.arguments?.getLong("id") ?: 0, nav)
        }
        composable("addSchedule") { AddSchedule(nav) }
        composable("addUser") { AddUser(nav) }
        composable("createGroup") { CreateGroup(nav) }
    }
}

@Composable
fun MainScreen(nav: NavController) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf("日程", "群聊", "同事").forEachIndexed { i, t ->
                    NavigationBarItem(
                        icon = { Icon(if (i==0) Icons.Default.CalendarToday else if(i==1) Icons.Default.Chat else Icons.Default.Person, null) },
                        label = { Text(t) },
                        selected = tab == i,
                        onClick = { tab = i }
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                when(tab){
                    0 -> nav.navigate("addSchedule")
                    1 -> nav.navigate("createGroup")
                    2 -> nav.navigate("addUser")
                }
            }) { Icon(Icons.Default.Add, null) }
        }
    ) { p ->
        Box(Modifier.padding(p)) {
            when(tab) {
                0 -> CalendarTab()
                1 -> GroupTab(nav)
                2 -> ContactTab()
            }
        }
    }
}

@Composable
fun CalendarTab() {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var cal by remember { mutableStateOf(Calendar.getInstance()) }
    var scheds by remember { mutableStateOf<List<ScheduleEntity>>(emptyList()) }

    fun load() {
        scope.launch {
            scheds = withContext(Dispatchers.IO) { db.scheduleDao().getAll() }
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { cal.add(Calendar.MONTH, -1); load() }) { Icon(Icons.Default.ChevronLeft, null) }
            Text(SimpleDateFormat("yyyy年MM月", Locale.CHINA).format(cal.time), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = { cal.add(Calendar.MONTH, 1); load() }) { Icon(Icons.Default.ChevronRight, null) }
        }

        Row { listOf("日","一","二","三","四","五","六").forEach {
            Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = Color.Gray)
        } }

        val first = cal.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        val offset = first.get(Calendar.DAY_OF_WEEK) - 1
        var day = 1
        val max = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        for (r in 0..5) {
            Row {
                for (c in 0..6) {
                    val idx = r*7+c
                    val d = if (idx < offset || day > max) null else day++
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                            .background(if (d != null) Color(0xFFE3F2FD) else Color.Transparent, RoundedCornerShape(4.dp)),
                        Alignment.Center
                    ) {
                        if (d != null) Text(d.toString(), fontSize = 14.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("我的日程 (${scheds.size})", fontWeight = FontWeight.Bold)
        LazyColumn {
            items(scheds) {
                Card(Modifier.fillMaxWidth().padding(4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (it.isLeaderTask) Color(0xFFFFF3E0) else Color.White)) {
                    Row(Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(it.title)
                            Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(it.dateTime)), fontSize = 12.sp, color = Color.Gray)
                        }
                        Icon(Icons.Default.Notifications, null, tint = if (it.remindMinutes > 0) Color(0xFFFF9800) else Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun GroupTab(nav: NavController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<GroupEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        scope.launch { groups = withContext(Dispatchers.IO) { db.groupDao().getAll() } }
    }

    LazyColumn(Modifier.padding(16.dp)) {
        items(groups) {
            Card(Modifier.fillMaxWidth().padding(4.dp).clickable { nav.navigate("chat/${it.id}") }) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Default.Group, null, Modifier.size(36.dp), Color(0xFF2196F3))
                    Spacer(Modifier.width(12.dp))
                    Text(it.groupName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun ChatScreen(id: Long, nav: NavController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(id) {
        scope.launch { msgs = withContext(Dispatchers.IO) { db.messageDao().getByGroup(id) } }
    }

    Column {
        TopAppBar(title = { Text("群聊") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, null) } })
        LazyColumn(Modifier.weight(1f).padding(8.dp), reverseLayout = true) {
            items(msgs.reversed()) {
                Card(Modifier.fillMaxWidth().padding(4.dp),
                    colors = CardDefaults.cardColors(containerColor = if (it.senderPhone=="13800001111") Color(0xFFE3F2FD) else Color(0xFFF5F5F5))) {
                    Text(it.content, Modifier.padding(12.dp))
                }
            }
        }
        Row(Modifier.padding(8.dp)) {
            TextField(text, { text=it }, Modifier.weight(1f), placeholder={ Text("输入") })
            Button({
                if (text.isNotBlank()) {
                    scope.launch {
                        withContext(Dispatchers.IO) { db.messageDao().insertMessage(MessageEntity(groupId=id, senderPhone="13800001111", content=text)) }
                        msgs = withContext(Dispatchers.IO) { db.messageDao().getByGroup(id) }
                        text = ""
                    }
                }
            }) { Text("发送") }
        }
    }
}

@Composable
fun AddSchedule(nav: NavController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var isLeader by remember { mutableStateOf(false) }
    var remind by remember { mutableStateOf(10f) }

    Column(Modifier.padding(16.dp)) {
        TopAppBar(title = { Text("新建日程") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.Close, null) } })
        OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("标题") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("领导安排")
            Switch(checked = isLeader, onCheckedChange = { isLeader = it })
        }
        Text("提前 ${remind.toInt()} 分钟")
        Slider(value = remind, onValueChange = { remind = it }, steps = 5, valueRange = 0f..30f)
        Button({
            scope.launch {
                val s = ScheduleEntity(
                    title = title,
                    dateTime = System.currentTimeMillis() + 3600000,
                    remindMinutes = remind.toInt(),
                    isLeaderTask = isLeader,
                    creatorPhone = "13800001111"
                )
                withContext(Dispatchers.IO) { db.scheduleDao().insertSchedule(s) }
                scheduleReminder(ctx, s)
                nav.popBackStack()
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("保存并提醒")
        }
    }
}

@Composable
fun AddUser(nav: NavController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp)) {
        TopAppBar(title = { Text("添加同事") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.Close, null) } })
        OutlinedTextField(value = phone, onValueChange = { phone = it }, modifier = Modifier.fillMaxWidth(), label = { Text("手机号") })
        OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("姓名") })
        Button({
            if (phone.isNotBlank() && name.isNotBlank()) {
                scope.launch {
                    withContext(Dispatchers.IO) { db.userDao().insertUser(UserEntity(phone, name)) }
                    msg = "添加成功！"
                    phone = ""; name = ""
                }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("添加")
        }
        Text(msg, color = Color.Green)
    }
}

@Composable
fun CreateGroup(nav: NavController) {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var gname by remember { mutableStateOf("") }
    var phones by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp)) {
        TopAppBar(title = { Text("创建群组") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Default.Close, null) } })
        OutlinedTextField(value = gname, onValueChange = { gname = it }, modifier = Modifier.fillMaxWidth(), label = { Text("群名") })
        OutlinedTextField(value = phones, onValueChange = { phones = it }, modifier = Modifier.fillMaxWidth(), label = { Text("成员手机号(逗号分隔)") })
        Button({
            if (gname.isNotBlank() && phones.isNotBlank()) {
                scope.launch {
                    val list = phones.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    withContext(Dispatchers.IO) { db.groupDao().insertGroup(GroupEntity(groupName = gname, memberPhones = list)) }
                    msg = "创建成功！"
                    gname = ""; phones = ""
                }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("创建")
        }
        Text(msg, color = Color.Green)
    }
}

@Composable
fun ContactTab() {
    val ctx = LocalContext.current
    val db = AppDatabase.get(ctx)
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<UserEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        scope.launch { list = withContext(Dispatchers.IO) { db.userDao().getAll() } }
    }

    LazyColumn(Modifier.padding(16.dp)) {
        items(list) {
            Card(Modifier.fillMaxWidth().padding(4.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Default.Person, null)
                    Spacer(Modifier.width(12.dp))
                    Text("${it.name} (${it.phone})")
                }
            }
        }
    }
}
