package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AutoMateDatabase
import com.example.data.local.entity.MenuReplyEntity
import com.example.engine.MenuOption
import com.example.engine.MenuReplyManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MenuReplyManagerTest {

    private lateinit var db: AutoMateDatabase
    private lateinit var manager: MenuReplyManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AutoMateDatabase::class.java
        ).allowMainThreadQueries().build()
        manager = MenuReplyManager(
            menuReplyDao = db.menuReplyDao(),
            json = Json { ignoreUnknownKeys = true }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `buildMenuText formats options accurately`() {
        val options = listOf(
            MenuOption(1, "خدمة العملاء", "أهلاً بك في خدمة العملاء"),
            MenuOption(2, "المبيعات", "أهلاً بك في المبيعات"),
            MenuOption(3, "الدعم الفني", "أهلاً بك في الدعم الفني")
        )
        val menu = MenuReplyEntity(
            id = 1L,
            trigger = "القائمة",
            title = "",
            optionsJson = manager.encodeOptions(options)
        )

        val expected = "1. خدمة العملاء\n2. المبيعات\n3. الدعم الفني\n\nأرسل رقم الخيار:"
        assertEquals(expected, manager.buildMenuText(menu))
    }

    @Test
    fun `handleReply returns response for matching option number and null for invalid`() {
        val options = listOf(
            MenuOption(1, "خدمة العملاء", "رد خدمة العملاء"),
            MenuOption(2, "المبيعات", "رد المبيعات")
        )
        val menu = MenuReplyEntity(
            id = 1L,
            trigger = "القائمة",
            title = "اختر",
            optionsJson = manager.encodeOptions(options)
        )

        assertEquals("رد خدمة العملاء", manager.handleReply("1", menu))
        assertEquals("رد المبيعات", manager.handleReply(" 2 ", menu))
        assertNull(manager.handleReply("99", menu))
        assertNull(manager.handleReply("نص عشوائي", menu))
        assertNull(manager.handleReply("1", null))
    }

    @Test
    fun `processConversationMessage triggers menu then resolves option selection`() = runTest {
        val options = listOf(
            MenuOption(1, "الأسعار", "قائمة الأسعار تبدأ من 50 ريال"),
            MenuOption(2, "الموقع", "الرياض - طريق الملك فهد")
        )
        db.menuReplyDao().insert(
            MenuReplyEntity(
                trigger = "مساعدة",
                title = "كيف نساعدك؟",
                optionsJson = manager.encodeOptions(options),
                enabled = true
            )
        )

        val convKey = "com.whatsapp:أحمد"
        val promptReply = manager.processConversationMessage(convKey, "مساعدة")
        assertEquals("كيف نساعدك؟\n1. الأسعار\n2. الموقع\n\nأرسل رقم الخيار:", promptReply)

        val choiceReply = manager.processConversationMessage(convKey, "2")
        assertEquals("الرياض - طريق الملك فهد", choiceReply)

        // Conversation state cleared after valid selection
        assertNull(manager.processConversationMessage(convKey, "2"))
    }
}
