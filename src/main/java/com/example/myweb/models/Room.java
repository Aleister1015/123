package com.example.myweb.models;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.*;

/**
 * 遊戲房間資料模型（MongoDB）
 */
@Document(collection = "rooms")
public class Room {

    /* ========== 基本欄位 ========== */
    @Id
    private String id;
    private String roomName;
    private int    playerCount;
    private String roomType;          // public / private
    private String roomPassword;

    private List<String>         players   = new ArrayList<>();
    private Map<String,String>   avatarMap = new HashMap<>();

    /* 角色相關 */
    private Map<String, RoleInfo> assignedRoles = new HashMap<>();

    /* 狀態旗標 */
    private boolean started = false;

    /* 當前領袖 */
    private String currentLeader;

    /* ========== 🔥 投票相關欄位 ========== */
    /** 本輪被提名出戰的玩家清單 */
    private List<String> currentExpedition = new ArrayList<>();    // 🔥 新增

    /** 投票結果：玩家 → true(同意) / false(反對) */
    private Map<String, Boolean> voteMap = new HashMap<>();        // 🔥 新增

    private Map<Integer, MissionRecord> missionResults = new HashMap<>();
    private Map<String, String> submittedMissionCards = new HashMap<>();
    private int currentRound = 1;
    /* ========== Getter / Setter ========== */

    public String getId() { return id; }
    public void   setId(String id) { this.id = id; }

    public String getRoomName() { return roomName; }
    public void   setRoomName(String roomName) { this.roomName = roomName; }

    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }

    public String getRoomType() { return roomType; }
    public void   setRoomType(String roomType) { this.roomType = roomType; }

    public String getRoomPassword() { return roomPassword; }
    public void   setRoomPassword(String roomPassword) { this.roomPassword = roomPassword; }

    public List<String> getPlayers() { return players; }
    public void         setPlayers(List<String> players) { this.players = players; }

    public Map<String,String> getAvatarMap() { return avatarMap; }
    public void               setAvatarMap(Map<String,String> avatarMap) { this.avatarMap = avatarMap; }

    public Map<String,RoleInfo> getAssignedRoles() { return assignedRoles; }
    public void                 setAssignedRoles(Map<String,RoleInfo> assignedRoles) { this.assignedRoles = assignedRoles; }

    public boolean isStarted() { return started; }
    public void    setStarted(boolean started) { this.started = started; }

    public String getCurrentLeader() { return currentLeader; }
    public void   setCurrentLeader(String currentLeader) { this.currentLeader = currentLeader; }

    /* ---------- 🔥 投票欄位 Getter / Setter ---------- */
    public List<String> getCurrentExpedition() { return currentExpedition; }
    public void setCurrentExpedition(List<String> currentExpedition) { this.currentExpedition = currentExpedition; }

    public Map<String, Boolean> getVoteMap() { return voteMap; }
    public void setVoteMap(Map<String, Boolean> voteMap) { this.voteMap = voteMap; }

    /* ========== 內部類：角色資訊 ========== */
    public static class RoleInfo {
        private String name;   // 角色名稱
        private String image;  // 對應圖片檔

        public RoleInfo() {}
        public RoleInfo(String name, String image) {
            this.name  = name;
            this.image = image;
        }

        public String getName()  { return name;  }
        public void   setName(String name)  { this.name = name; }

        public String getImage() { return image; }
        public void   setImage(String image) { this.image = image; }
    }
    public int getCurrentRound() {
    return currentRound;
    }

    public void setCurrentRound(int currentRound) {
        this.currentRound = currentRound;
    }

    public Map<Integer, MissionRecord> getMissionResults() {
        return missionResults;
    }

    public void setMissionResults(Map<Integer, MissionRecord> missionResults) {
        this.missionResults = missionResults;
    }

    public Map<String, String> getSubmittedMissionCards() {
        return submittedMissionCards;
    }

    public void setSubmittedMissionCards(Map<String, String> submittedMissionCards) {
        this.submittedMissionCards = submittedMissionCards;
    }
}
