const urlParams = new URLSearchParams(window.location.search);  
const roomId = urlParams.get("roomId");
const playerName = sessionStorage.getItem("playerName");

let players = [];
let myRole = null;

const positions = [
  { top: '3%', left: '55%' },
  { top: '3%', right: '55%' },
  { top: '40%', left: '20%' },
  { top: '40%', right: '20%' },
  { bottom: '30px', left: '50%', transform: 'translateX(-50%)' }
];

async function fetchPlayers() {
  try {
    const response = await fetch(`/api/room/${roomId}/players`);
    players = await response.json();
    renderPlayers(players);
  } catch (err) {
    console.error("❌ 無法載入玩家資料", err);
  }
}

async function fetchAssignedRoles() {
  try {
    const response = await fetch(`/api/room/${roomId}/players`);
    players = await response.json();

    const roleRes = await fetch(`/api/room/${roomId}/roles`);
    if (!roleRes.ok) throw new Error("角色 API 失敗");

    const rolesMap = await roleRes.json();
    console.log("🎭 從資料庫取得角色資訊", rolesMap);

    applyRolesToPlayers(rolesMap);
  } catch (err) {
    console.error("❌ 無法取得角色資料", err);
  }
}

function applyRolesToPlayers(rolesMap) {
  const assigned = players.map(p => ({
    ...p,
    role: rolesMap[p.name]
  }));

  renderPlayers(assigned);

  const self = assigned.find(p => p.name === playerName);
  if (self) {
    myRole = self.role;
    console.log("👤 我的角色是：", myRole);
  }
}

function renderPlayers(players) {
  const container = document.getElementById("player-container");
  container.innerHTML = "";

  players.forEach((player, index) => {
    const card = document.createElement("div");
    const isSelf = player.name === playerName;
    card.className = isSelf ? "player-self" : "player-card";

    Object.entries(positions[index] || {}).forEach(([key, value]) => {
      card.style[key] = value;
    });

    card.innerHTML = `
      <div class="avatar">
        <img src="/images/${player.avatar}" alt="${player.name}">
      </div>
      <div class="name">${player.name}</div>
      ${
        isSelf && player.role
          ? `<div class="role-label">角色：${player.role.name}</div>`
          : ""
      }
    `;

    container.appendChild(card);
  });
}

function connectWebSocket() {
  const socket = new SockJS('/ws');
  const stompClient = Stomp.over(socket);

  stompClient.connect({}, () => {
    stompClient.subscribe(`/topic/room/${roomId}`, async (message) => {
      const msg = message.body.trim();
      console.log("🛰️ WebSocket:", msg);

      if (msg === "startRealGame") {
        await fetchAssignedRoles(); // 收到後端廣播 → 撈角色
      }
    });
  });
}

document.addEventListener("DOMContentLoaded", async () => {
  await fetchPlayers();            // 預先抓一次玩家（含頭貼）
  await fetchAssignedRoles();     // 抓角色顯示自己角色
  connectWebSocket();             // 監聽跳轉事件
});
