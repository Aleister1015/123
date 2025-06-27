/**
 * RoomController.java
 *
 * ▶ 此檔案為多人遊戲的核心控制器，處理所有「房間相關功能」的 REST API。
 *
 * ▶ 功能總覽：
 *   - 房間建立、加入、退出與查詢（create-room, join-room, getAllRooms...）
 *   - 頭貼選擇、角色指派與隨機領袖設定
 *   - 開始遊戲、開始投票、投票、票數統計與結果
 *
 * ▶ 與此控制器互動的單元：
 *   - RoomRepository：存取房間資料
 *   - RoomService：封裝較複雜的遊戲邏輯（例如投票流程）
 *   - SimpMessagingTemplate：用來透過 WebSocket 廣播開始訊息與事件更新
 *   - 前端 JavaScript 呼叫 `/api/` 下的路由與 WebSocket 訂閱 `/topic/room/{roomId}`
 *
 * ▶ 特色說明：
 *   - 支援動態角色分配，支援 5~10 人不同配置
 *   - 房主判定、動態人數驗證、所有玩家頭貼確認後才廣播開始
 *   - 投票過程包含發起投票、投票行為、投票狀態查詢與票數統計
 *
 * ▶ 備註：
 *   - 本控制器幾乎涵蓋整個遊戲流程，是邏輯密度最高的類別之一
 *   - 若有修改遊戲流程或房間管理邏輯，請從這裡進入
 */

package com.example.myweb.controllers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.myweb.dto.AvatarSelectionRequest;
import com.example.myweb.models.Room;
import com.example.myweb.repositories.RoomRepository;
import com.example.myweb.service.RoomService;

@RestController
@RequestMapping("/api")
public class RoomController {

    @Autowired private RoomRepository        roomRepository;
    @Autowired private RoomService           roomService;          // ★ 新增
    @Autowired private SimpMessagingTemplate simpMessagingTemplate;

    /* -------------------- 建房 -------------------- */
// 前端建立房間時會送出房名與創建者名稱，若重複就回錯誤訊息，否則存入資料庫。
    @PostMapping("/create-room")
    public ResponseEntity<Object> createRoom(@RequestBody Room room,
                                             @RequestParam String playerName) {

        String formattedRoomName = room.getRoomName() + "房間";
        room.setRoomName(formattedRoomName);

        boolean exists = roomRepository.findAll().stream()
                            .anyMatch(r -> r.getRoomName().equals(room.getRoomName()));
        if (exists)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                 .body("房間名稱已存在，請選擇其他名稱！");

        room.setId(UUID.randomUUID().toString());
        if (!"private".equals(room.getRoomType())) room.setRoomPassword(null);

        room.setPlayers(new ArrayList<>(List.of(playerName)));
        roomRepository.save(room);
        return ResponseEntity.ok(room);
    }

   /* -------------------- 取得房間資料 -------------------- */
    // 根據房間 ID 回傳對應房間資料，或 404。

    @GetMapping("/room/{roomId}")
    public ResponseEntity<Room> getRoomById(@PathVariable String roomId) {
        return roomRepository.findById(roomId)
               .map(ResponseEntity::ok)
               .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(null));
    }
// 回傳所有尚未開始的房間，用於大廳顯示。
    @GetMapping("/rooms")
    public ResponseEntity<List<Room>> getAllRooms() {
        List<Room> rooms = roomRepository.findAll().stream()
                             .filter(r -> !r.isStarted())
                             .collect(Collectors.toList());
        return ResponseEntity.ok(rooms);
    }

    /* -------------------- 加入與退出房間 -------------------- */
    // 加入房間時檢查：是否存在、人數是否滿、玩家是否重複。

    @PostMapping("/join-room")
    public ResponseEntity<Object> joinRoom(@RequestParam String roomId,
                                           @RequestParam String playerName,
                                           @RequestParam(required = false) String roomPassword) {

        Optional<Room> opt = roomRepository.findById(roomId);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("找不到房間");

        Room room = opt.get();
        List<String> players = room.getPlayers();

        if (players.size() >= room.getPlayerCount())
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("房間人數已滿");

        if (players.contains(playerName))
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("玩家已經加入房間");

        players.add(playerName);
        roomRepository.save(room);
        return ResponseEntity.ok(Map.of("success", true, "message", "加入房間成功"));
    }
// 離開房間；若是最後一人則刪除房間。
    @PostMapping("/exit-room")
    public ResponseEntity<Object> exitRoom(@RequestParam String roomId,
                                           @RequestParam String playerName) {

        Optional<Room> opt = roomRepository.findById(roomId);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("找不到房間");

        Room room = opt.get();
        List<String> players = room.getPlayers();

        if (!players.remove(playerName))
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("該玩家不在此房間");

        if (players.isEmpty()) {
            roomRepository.delete(room);
            return ResponseEntity.ok(Map.of("success", true, "message", "退出房間成功，房間已刪除"));
        }
        roomRepository.save(room);
        return ResponseEntity.ok(Map.of("success", true, "message", "退出房間成功"));
    }

   /* -------------------- 房主開始遊戲（發送 WebSocket） -------------------- */
    // 僅房主（players[0]）可啟動遊戲，並向所有人廣播 startGame 訊息。

    @PostMapping("/start-game")
    public ResponseEntity<Object> startGame(@RequestParam String roomId,
                                            @RequestParam String playerName) {

        Optional<Room> opt = roomRepository.findById(roomId);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("找不到房間");

        Room room = opt.get();
        List<String> players = room.getPlayers();
        if (players.isEmpty() || !players.get(0).equals(playerName))
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("只有房主可以開始遊戲");

        room.setStarted(true);
        roomRepository.save(room);
        simpMessagingTemplate.convertAndSend("/topic/room/" + roomId, "startGame");
        return ResponseEntity.ok(Map.of("success", true, "message", "遊戲開始訊息已廣播"));
    }

   /* -------------------- 玩家選頭貼邏輯 -------------------- */
    // 玩家選擇頭像後儲存，並廣播「誰選好了」；若所有人都選好，再廣播 allAvatarSelected。

    @PostMapping("/room/{roomId}/select-avatar")
    public ResponseEntity<?> selectAvatar(@PathVariable String roomId,
                                          @RequestBody AvatarSelectionRequest req) {

        String playerName = req.getPlayerName();
        String avatar     = req.getAvatar();

        Optional<Room> opt = roomRepository.findById(roomId);
        if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("房間不存在");

        Room room = opt.get();
        if (!room.getPlayers().contains(playerName))
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("該玩家不在此房間");

        room.getAvatarMap().put(playerName, avatar);
        roomRepository.save(room);

        simpMessagingTemplate.convertAndSend("/topic/room/" + roomId, "avatarSelected:" + playerName);

        if (room.getAvatarMap().size() >= room.getPlayerCount())
            simpMessagingTemplate.convertAndSend("/topic/room/" + roomId, "allAvatarSelected");

        return ResponseEntity.ok().build();
    }

    /* -------------------- 角色一次分配（舊流程） -------------------- */

        /** 角色一次分配（舊流程，改成 5–10 人都支援） */
        @PostMapping("/start-real-game")
public ResponseEntity<Map<String, Room.RoleInfo>> startRealGame(
        @RequestParam String roomId,
        @RequestParam String playerName) {

    Optional<Room> opt = roomRepository.findById(roomId);
    if (opt.isEmpty()) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    Room room = opt.get();

    System.out.println("✅ 進入 startRealGame：roomId = " + roomId);
    System.out.println("👉 目前已指派角色數量：" + (room.getAssignedRoles() == null ? 0 : room.getAssignedRoles().size()));

    // 如果已指派過，就直接回傳舊結果
    if (room.getAssignedRoles() != null && !room.getAssignedRoles().isEmpty()) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                             .body(room.getAssignedRoles());
    }

    List<String> players = new ArrayList<>(room.getPlayers());
    List<Room.RoleInfo> roles;

    roles = switch (players.size()) {
        case 5 -> Arrays.asList(
            new Room.RoleInfo("工程師",      "goodpeople1.png"),
            new Room.RoleInfo("普通倖存者", "goodpeople4.png"),
            new Room.RoleInfo("普通倖存者", "goodpeople4.png"),
            new Room.RoleInfo("潛伏者",     "badpeople1.png"),
            new Room.RoleInfo("邪惡平民",   "badpeople4.png")
        );
        case 6 -> Arrays.asList(
            new Room.RoleInfo("指揮官",     "goodpeople3.png"),
            new Room.RoleInfo("工程師",     "goodpeople1.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("潛伏者",     "badpeople1.png"),
            new Room.RoleInfo("邪惡平民",   "badpeople4.png")
        );
        case 7 -> Arrays.asList(
            new Room.RoleInfo("指揮官",     "goodpeople3.png"),
            new Room.RoleInfo("工程師",     "goodpeople1.png"),
            new Room.RoleInfo("醫護兵",     "goodpeople2.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("潛伏者",     "badpeople1.png"),
            new Room.RoleInfo("破壞者",     "badpeople2.png"),
            new Room.RoleInfo("邪惡平民",   "badpeople4.png")
        );
        case 8 -> Arrays.asList(
            new Room.RoleInfo("指揮官",     "goodpeople3.png"),
            new Room.RoleInfo("工程師",     "goodpeople1.png"),
            new Room.RoleInfo("醫護兵",     "goodpeople2.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("潛伏者",     "badpeople1.png"),
            new Room.RoleInfo("破壞者",     "badpeople2.png"),
            new Room.RoleInfo("邪惡平民",   "badpeople4.png")
        );
        case 9 -> Arrays.asList(
            new Room.RoleInfo("指揮官",     "goodpeople3.png"),
            new Room.RoleInfo("工程師",     "goodpeople1.png"),
            new Room.RoleInfo("醫護兵",     "goodpeople2.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("潛伏者",     "badpeople1.png"),
            new Room.RoleInfo("破壞者",     "badpeople2.png"),
            new Room.RoleInfo("影武者",     "badpeople3.png")
        );
        case 10 -> Arrays.asList(
            new Room.RoleInfo("指揮官",     "goodpeople3.png"),
            new Room.RoleInfo("工程師",     "goodpeople1.png"),
            new Room.RoleInfo("醫護兵",     "goodpeople2.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("普通倖存者","goodpeople4.png"),
            new Room.RoleInfo("潛伏者",     "badpeople1.png"),
            new Room.RoleInfo("破壞者",     "badpeople2.png"),
            new Room.RoleInfo("影武者",     "badpeople3.png"),
            new Room.RoleInfo("邪惡平民",   "badpeople4.png")
        );
        default -> throw new IllegalArgumentException("尚未支援此人數的遊戲模式");
    };

    // 安全檢查
    if (roles.size() != players.size()) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                             .body(Map.of("error",
                                 new Room.RoleInfo("錯誤", "角色數量與玩家人數不符")));
    }

    Collections.shuffle(players);
    Collections.shuffle(roles);

    Map<String, Room.RoleInfo> assigned = new HashMap<>();
    for (int i = 0; i < players.size(); i++) {
        assigned.put(players.get(i), roles.get(i));
    }

    room.setAssignedRoles(assigned);
    roomRepository.save(room);
    simpMessagingTemplate.convertAndSend("/topic/room/" + roomId, "startRealGame");

    return ResponseEntity.ok(assigned);
}


    /* -------------------- 取玩家列表 -------------------- */

    @GetMapping("/room/{roomId}/players")
    public ResponseEntity<List<Map<String, String>>> getAllPlayers(@PathVariable String roomId) {

        return roomRepository.findById(roomId)
            .map(room -> {
                List<Map<String, String>> list = new ArrayList<>();
                room.getAvatarMap().forEach((name, avatar) -> {
                    list.add(Map.of("name", name, "avatar", avatar));
                });
                return ResponseEntity.ok(list);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /* -------------------- 取角色 + 領袖（唯一實作） -------------------- */

    @GetMapping("/room/{roomId}/roles")
    public ResponseEntity<Map<String,Object>> getRolesAndLeader(@PathVariable String roomId){

        return roomRepository.findById(roomId)
            .map(room -> {
                Map<String,Object> res = new HashMap<>();
                res.put("assignedRoles", room.getAssignedRoles());
                res.put("currentLeader", room.getCurrentLeader());
                return ResponseEntity.ok(res);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /* -------------------- 指派角色 + 隨機領袖 -------------------- */


    @PostMapping("/room/{roomId}/assign-roles")
    public Map<String,Object> assignRoles(@PathVariable String roomId){

        Room room = roomService.assignRoles(roomId);

        Map<String,Object> res = new HashMap<>();
        res.put("assignedRoles", room.getAssignedRoles());
        res.put("currentLeader", room.getCurrentLeader());
        return res;
    }

    /* =================================================
       🔥 投  票  相  關  端  點
       ================================================= */

    /** 開始投票（領袖送 expedition） */
    @PostMapping("/room/{roomId}/start-vote")
    public ResponseEntity<Void> startVote(
            @PathVariable String roomId,
            @RequestBody Map<String,Object> body) {

        @SuppressWarnings("unchecked")             // 🔥 修正：消除未檢查 cast 警告
        List<String> expedition = (List<String>) body.get("expedition");
        String leader = (String) body.get("leader");

        roomService.startVote(roomId, expedition, leader);   // 🔥 修正：改用 roomService
        return ResponseEntity.ok().build();
    }

    /** 玩家投票 */
    @PostMapping("/room/{roomId}/vote")
public ResponseEntity<Map<String,Object>> vote(
        @PathVariable String roomId,
        @RequestBody Map<String,Object> body) {

    String voter = (String) body.get("voter");
    Boolean agreeObj = (Boolean) body.get("agree");

    if (voter == null || agreeObj == null) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            Map.of("success", false, "message", "缺少 voter 或 agree 參數"));
    }

    boolean agree = agreeObj;

    Map<String,Object> result = roomService.castVote(roomId, voter, agree);
    return ResponseEntity.ok(result);
}


    /** 取得目前票數與自身能否投票 */
    @GetMapping("/room/{roomId}/vote-state")
    public ResponseEntity<Map<String,Object>> voteState(
            @PathVariable String roomId,
            @RequestParam String player) {

        Map<String,Object> state = roomService.getVoteState(roomId, player);    // 🔥 修正
        return ResponseEntity.ok(state);
    }
    @GetMapping("/game-start/{roomId}")
    public String gameStart(@PathVariable String roomId){
    return "game-front-page";   // 或你真正的遊戲模板名

    
}
    @GetMapping("/room/{roomId}/vote-result")
    public ResponseEntity<Map<String, Integer>> getVoteResult(@PathVariable String roomId) {
        Room room = roomService.getRoomById(roomId);
        if (room == null) {
            return ResponseEntity.notFound().build();
        }

        int agree = 0;
        int reject = 0;
        Map<String, Boolean> voteMap = room.getVoteMap();
        if (voteMap != null) {
            for (Boolean vote : voteMap.values()) {
                if (vote == null) continue; // 棄票
                if (vote) agree++;
                else reject++;
            }
        }

        Map<String, Integer> result = new HashMap<>();
        result.put("agree", agree);
        result.put("reject", reject);
        return ResponseEntity.ok(result);
    }
    @GetMapping("/skill/engineer-scan")
public ResponseEntity<Map<String, Object>> engineerScan(
        @RequestParam String roomId,
        @RequestParam String target) {

    Optional<Room> opt = roomRepository.findById(roomId);
    if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

    Room room = opt.get();
    Room.RoleInfo info = room.getAssignedRoles().get(target);
    if (info == null) return ResponseEntity.badRequest().build();

    boolean isEvil = info.getName().equals("潛伏者")
                  || info.getName().equals("破壞者")
                  || info.getName().equals("影武者")
                  || info.getName().equals("邪惡平民");

    return ResponseEntity.ok(Map.of("isEvil", isEvil));
}

@PostMapping("/room/{roomId}/submit-mission")
public ResponseEntity<?> submitMissionCard(@PathVariable String roomId, @RequestBody Map<String, String> body) {
    String playerName = body.get("playerName");
    String result     = body.get("result"); // "success" or "fail"

    Optional<Room> opt = roomRepository.findById(roomId);
    if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("房間不存在");

    Room room = opt.get();

    if (!room.getCurrentExpedition().contains(playerName)) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("非任務成員不能提交任務卡");
    }

    room.getMissionCards().put(playerName, result);
    roomRepository.save(room);

    System.out.println("📝 " + playerName + " 提交了任務卡：" + result);
    System.out.println("🧾 已提交卡數：" + room.getMissionCards().size() + " / 出任務人數：" + room.getCurrentExpedition().size());

    boolean allSubmitted = room.getMissionCards().size() >= room.getCurrentExpedition().size();
    if (allSubmitted) {
    System.out.println("📋 當前 Expedition 名單：" + room.getCurrentExpedition());
System.out.println("📦 已提交的 Mission Cards：" + room.getMissionCards().keySet());

    simpMessagingTemplate.convertAndSend("/topic/room/" + roomId, "allMissionCardsSubmitted");
}


    return ResponseEntity.ok(Map.of(
        "success", true,
        "allSubmitted", allSubmitted
    ));
}



@PostMapping("/room/{roomId}/use-skill")
public ResponseEntity<Map<String, Object>> useSkill(
        @PathVariable String roomId,
        @RequestParam String playerName) {

    Optional<Room> opt = roomRepository.findById(roomId);
    if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);

    Room room = opt.get();

    // ✅ 如果該玩家已用過技能就略過
    if (room.getFinishedSkills().contains(playerName)) {
        return ResponseEntity.ok(Map.of("success", true, "message", "技能已使用過"));
    }

    // 加入技能完成名單
    room.getFinishedSkills().add(playerName);
    roomRepository.save(room);

    // ✅ 改為檢查「全體玩家」都使用完技能
    boolean allDone = room.getFinishedSkills().containsAll(room.getPlayers());

    if (allDone) {
        room.setSkillPhaseFinished(true);
        roomRepository.save(room);
        simpMessagingTemplate.convertAndSend("/topic/room/" + roomId, "allSkillUsed");
    }

    return ResponseEntity.ok(Map.of(
        "success", true,
        "allSkillUsed", allDone
    ));
}
@PostMapping("/room/{roomId}/mission-result")
public ResponseEntity<?> missionResult(@PathVariable String roomId) {
    Optional<Room> opt = roomRepository.findById(roomId);
    if (opt.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body("房間不存在");

    Room room = opt.get();

    // ✅ 1. 計算任務卡成功與失敗數量
    int success = 0;
    int fail = 0;
    for (String result : room.getMissionCards().values()) {
        if ("success".equals(result)) success++;
        else if ("fail".equals(result)) fail++;
    }

    // ✅ 2. 決定是否任務成功（可自訂條件）
    boolean missionSuccess = (fail == 0);  // 所有人都投 success 才算成功

    // ✅ 3. 廣播結果（你也可以選擇不廣播，直接前端 fetch）
    simpMessagingTemplate.convertAndSend("/topic/room/" + roomId,
        missionSuccess ? "missionSuccess" : "missionFail");

    // ✅ 4. 清空所有任務 / 技能 / 投票相關資料
    room.setMissionCards(new HashMap<>());
    room.setMissionSubmitted(false);
    room.setFinishedMissions(new HashSet<>());

    room.setFinishedSkills(new HashSet<>());
    room.setSkillPhaseFinished(false);

    room.setCurrentExpedition(new ArrayList<>());
    room.setVoteMap(new HashMap<>());

    roomRepository.save(room);

    // ✅ 5. 回傳結算結果
    return ResponseEntity.ok(Map.of(
        "success", missionSuccess,
        "successCount", success,
        "failCount", fail
    ));
}

}



