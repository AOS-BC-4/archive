package com.likelion.liontalk.features.chatroom

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.likelion.liontalk.data.local.AppDatabase
import com.likelion.liontalk.data.local.entity.ChatMessageEntity
import com.likelion.liontalk.data.remote.dto.ChatMessageDto
import com.likelion.liontalk.data.remote.dto.PresenceMessageDto
import com.likelion.liontalk.data.remote.dto.TypingMessageDto
import com.likelion.liontalk.data.remote.mqtt.MqttClient
import com.likelion.liontalk.data.repository.ChatMessageRepository
import com.likelion.liontalk.data.repository.ChatRoomRepository
import com.likelion.liontalk.data.repository.UserPreferenceRepository
import com.likelion.liontalk.model.ChatMessage
import com.likelion.liontalk.model.ChatMessageMapper.toEntity
import com.likelion.liontalk.model.ChatUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatRoomViewModel(application: Application, private val roomId: Int) : ViewModel(){
    private val chatMessageRepository = ChatMessageRepository(application.applicationContext)
    private val chatRoomRepository = ChatRoomRepository(application.applicationContext)
//    val messages : LiveData<List<ChatMessageEntity>> = chatMessageRepository.getMessagesForRoom(roomId)

    // 시스템 메세지 리스트
    val _systemMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    //채팅 메세지
    val messages : StateFlow<List<ChatMessage>> = combine(
        chatMessageRepository.getMessagesForRoomFlow(roomId),
        _systemMessages
    ) {
        dbMessages, systemMessages ->
        (dbMessages + systemMessages).sortedBy { it.createdAt }
    }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
    )
//        val messages : StateFlow<List<ChatMessage>> = chatMessageRepository.getMessagesForRoomFlow(roomId)
//        .stateIn(
//            scope = viewModelScope,
//            started = SharingStarted.WhileSubscribed(5000),
//            initialValue = emptyList()
//        )

    private val userPreferenceRepository = UserPreferenceRepository.getInstance()

    val me : ChatUser get() = userPreferenceRepository.requireMe()

    private val _event = MutableSharedFlow<ChatRoomEvent>()
    val event = _event.asSharedFlow()

    init {
        viewModelScope.launch {

            withContext(Dispatchers.IO) {
                chatMessageRepository.syncFromServer(roomId)
            }

            withContext(Dispatchers.IO) {
                MqttClient.connect()
            }

            withContext(Dispatchers.IO) {
                subscribeToMqttTopics()
            }

            //최초 채팅방 진입시 입장 이벤트 전송
            publishEnterStatus()
        }
    }


    // 메세지 전송
    fun sendMessage(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dto = ChatMessageDto(
                roomId = roomId,
                sender = me,
                content = content,
                createdAt = System.currentTimeMillis()
            )
            // api send and local insert
            val responseDto = chatMessageRepository.sendMessage(dto)

            // mqtt send
            if (responseDto != null) {
                val json = Gson().toJson(responseDto)
                MqttClient.publish("liontalk/rooms/$roomId/message", json)

                //메세지 입력 종료 퍼블리시
                publishTypingStatus(false)

                //메세지 입력창 초기화 이벤트
                _event.emit(ChatRoomEvent.ClearInput)
            }
        }
    }

    // MQTT - methods
    private val topics = listOf("message","typing","enter","leave")
    //MQTT 구독 및 메세지 수신 처리
    private fun subscribeToMqttTopics() {
//        MqttClient.connect()
        MqttClient.setOnMessageReceived { topic, message -> handleIncomingMqttMessage(topic,message) }
        topics.forEach {
            MqttClient.subscribe("liontalk/rooms/$roomId/$it")
        }
    }
    //MQTT 구독 해지
    private fun unsubscribeFromMqttTopics() {
        topics.forEach {
            MqttClient.unsubscribe("liontalk/rooms/$roomId/$it")
        }
    }

    // MQTT 수신 메세지 처리
    private fun handleIncomingMqttMessage(topic: String,message: String) {
        when {
            topic.endsWith("/message") -> onReceivedMessage(message)
            topic.endsWith("/typing") -> onReceivedTyping(message)
            topic.endsWith("/enter") -> onReceivedEnter(message)
            topic.endsWith("/leave") -> onReceivedLeave(message)
        }
    }
    //채팅방 입장 메세지 핸들러
    private fun onReceivedEnter(message: String) {
        val dto = Gson().fromJson(message, PresenceMessageDto::class.java)
        if (dto.sender != me.name) {
            viewModelScope.launch {



                _event.emit(ChatRoomEvent.ChatRoomEnter(dto.sender))

                postSystemMessage("${dto.sender} 님이 입장하였습니다.")

                _event.emit(ChatRoomEvent.ScrollToBottom)
                //
            }
        }
    }
    //채팅방 퇴장 메세지 핸들러
    private fun onReceivedLeave(message: String) {
        val dto = Gson().fromJson(message, PresenceMessageDto::class.java)
        if (dto.sender != me.name) {
            viewModelScope.launch {
                _event.emit(ChatRoomEvent.ChatRoomLeave(dto.sender))

                postSystemMessage("${dto.sender} 님이 퇴장하였습니다.")

                _event.emit(ChatRoomEvent.ScrollToBottom)
            }
        }
    }

    private fun onReceivedMessage(message: String) {
        val dto = Gson().fromJson(message,ChatMessageDto::class.java)

        viewModelScope.launch {
//            chatMessageDao.insert(dto.toEntity())
            chatMessageRepository.receiveMessage(dto)

            _event.emit(ChatRoomEvent.ScrollToBottom)

            chatRoomRepository.updateLastReadMessageId(roomId,dto.id)
            chatRoomRepository.updateUnReadCount(roomId,0)
        }
    }
//    private val _event = MutableSharedFlow<ChatRoomEvent>()
//    val event = _event.asSharedFlow()

    private fun onReceivedTyping(message: String) {
        val dto = Gson(). fromJson(message, TypingMessageDto::class.java)
        if (dto.sender != me.name) {
            viewModelScope.launch {
                val event = if (dto.typing) ChatRoomEvent.TypingStarted(dto.sender)
                else ChatRoomEvent.TypingStopped

                _event.emit(event)
            }
        }
    }

    private fun postSystemMessage(content:String) {
        val systemMessage = ChatMessage(
            id = -1,
            roomId = roomId,
            sender = me,
            content = content,
            type = "system",
            createdAt = System.currentTimeMillis()
        )
        _systemMessages.value = _systemMessages.value + systemMessage
    }

    private var typing = false
    private var typingStopJop: Job? = null

    fun onTypingChanged(text: String) {
        if (text.isNotBlank() && !typing) {
            publishTypingStatus(true)
        }
        typingStopJop?.cancel()
        typingStopJop = viewModelScope.launch {
            delay(2000)
            typing = false
            publishTypingStatus(false)
        }
    }

    fun stopTyping() {
        typing = false
        publishTypingStatus(false)
        typingStopJop?.cancel()
    }

    // 채팅방 나가기
    fun leaveRoom(onComplete:() -> Unit) {
        viewModelScope.launch {
            publishLeaveStatus()

            onComplete()
        }
    }

    fun back(onComplete: () -> Unit) {
        viewModelScope.launch {
            unsubscribeFromMqttTopics()

            onComplete()
        }
    }

    // 메세지 입력 이벤트 퍼블리시
    private fun publishTypingStatus(isTyping:Boolean) {
        val json = Gson().toJson(TypingMessageDto(sender = me.name,isTyping))
        MqttClient.publish("liontalk/rooms/$roomId/typing", json)
    }

    // 채팅방 입장 이벤트 퍼블리시
    private fun publishEnterStatus() {
        val json = Gson().toJson(PresenceMessageDto( me.name))
        MqttClient.publish("liontalk/rooms/$roomId/enter",json)

        // 서버 및 로컬 입장 처리
        viewModelScope.launch {
            chatRoomRepository.enterRoom(me,roomId)

            val latestMessage = chatMessageRepository.getLatestMessage(roomId)

            latestMessage?.let {
                Log.d("publishEnterStatus", "latestMessage ${latestMessage.id}")
                chatRoomRepository.updateLastReadMessageId(roomId,it.id)

                chatRoomRepository.updateUnReadCount(roomId,0)
            }
        }
    }

    // 채팅방 퇴장 이벤트 퍼블리시
    private fun publishLeaveStatus() {
        val json = Gson().toJson(PresenceMessageDto(me.name))
        MqttClient.publish("liontalk/rooms/$roomId/leave",json)
    }
}