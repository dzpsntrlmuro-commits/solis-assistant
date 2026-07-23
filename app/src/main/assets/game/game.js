(() => {
  const PLAYER_HEIGHT = 1.65;
  const MOVE_SPEED = 3.2;
  const LOOK_SENS = 0.0028;
  const INTERACT_DIST = 2.6;

  const statusEl = document.getElementById("status");
  const promptEl = document.getElementById("prompt");
  const heldEl = document.getElementById("held");
  const titleOverlay = document.getElementById("title-overlay");
  const startBtn = document.getElementById("start-btn");

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(0xb8c4d4);
  scene.fog = new THREE.Fog(0xb8c4d4, 12, 28);

  const camera = new THREE.PerspectiveCamera(72, window.innerWidth / window.innerHeight, 0.08, 60);
  camera.position.set(0, PLAYER_HEIGHT, 4.2);

  const renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: "high-performance" });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.shadowMap.enabled = true;
  document.body.appendChild(renderer.domElement);

  const hemi = new THREE.HemisphereLight(0xf0f4ff, 0x6b5a45, 0.85);
  scene.add(hemi);
  const sun = new THREE.DirectionalLight(0xfff2d8, 0.95);
  sun.position.set(4, 8, 2);
  sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024);
  scene.add(sun);
  const lampLight = new THREE.PointLight(0xffd9a0, 0.55, 8);
  lampLight.position.set(-2.4, 1.8, -1.2);
  scene.add(lampLight);

  const colliders = [];
  const interactives = [];
  let heldObject = null;
  let doorOpen = false;
  let tvOn = false;
  let doorMesh = null;
  let tvScreenMat = null;
  let tvNoise = 0;
  let focused = null;
  let started = false;

  const yawPitch = { yaw: Math.PI, pitch: 0 };
  const keys = { forward: 0, strafe: 0 };
  const lookStick = { x: 0, y: 0 };
  const velocity = new THREE.Vector3();
  const clock = new THREE.Clock();

  function mat(color, opts = {}) {
    return new THREE.MeshStandardMaterial({
      color,
      roughness: opts.roughness ?? 0.75,
      metalness: opts.metalness ?? 0.05,
      emissive: opts.emissive ?? 0x000000,
      emissiveIntensity: opts.emissiveIntensity ?? 0,
    });
  }

  function box(w, h, d, color, x, y, z, opts = {}) {
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), mat(color, opts));
    mesh.position.set(x, y, z);
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    if (opts.rotY) mesh.rotation.y = opts.rotY;
    scene.add(mesh);
    if (opts.collide !== false) {
      colliders.push({
        minX: x - w / 2 - 0.05,
        maxX: x + w / 2 + 0.05,
        minZ: z - d / 2 - 0.05,
        maxZ: z + d / 2 + 0.05,
        yTop: y + h / 2,
        solid: true,
        mesh,
      });
    }
    return mesh;
  }

  function makeRoom() {
    // Floor / ceiling
    const floor = new THREE.Mesh(
      new THREE.PlaneGeometry(14, 12),
      mat(0xc4b39a, { roughness: 0.9 })
    );
    floor.rotation.x = -Math.PI / 2;
    floor.receiveShadow = true;
    scene.add(floor);

    const rug = new THREE.Mesh(
      new THREE.PlaneGeometry(3.4, 2.4),
      mat(0x7f5539, { roughness: 1 })
    );
    rug.rotation.x = -Math.PI / 2;
    rug.position.set(0.2, 0.01, 0.4);
    scene.add(rug);

    const ceiling = new THREE.Mesh(
      new THREE.PlaneGeometry(14, 12),
      mat(0xece7dc, { roughness: 1 })
    );
    ceiling.rotation.x = Math.PI / 2;
    ceiling.position.y = 2.7;
    scene.add(ceiling);

    // Outer walls
    box(14, 2.7, 0.2, 0xe8dcc8, 0, 1.35, -5.9);
    box(14, 2.7, 0.2, 0xe8dcc8, 0, 1.35, 5.9);
    box(0.2, 2.7, 12, 0xe2d6c0, -6.9, 1.35, 0);
    box(0.2, 2.7, 12, 0xe2d6c0, 6.9, 1.35, 0);

    // Partition wall with door opening
    box(4.2, 2.7, 0.18, 0xddd0ba, -4.5, 1.35, 1.2);
    box(4.2, 2.7, 0.18, 0xddd0ba, 4.5, 1.35, 1.2);
    box(2.0, 0.55, 0.18, 0xddd0ba, 0, 2.42, 1.2); // lintel

    // Door
    doorMesh = box(1.85, 2.15, 0.08, 0x8b5e3c, 0, 1.075, 1.2, { collide: false, roughness: 0.55 });
    doorMesh.userData = { kind: "door", label: "Kapı" };
    interactives.push(doorMesh);
    colliders.push({
      minX: -0.95, maxX: 0.95, minZ: 1.05, maxZ: 1.35, yTop: 2.2, solid: true, mesh: doorMesh, dynamic: "door"
    });

    // Window niches
    box(1.8, 1.2, 0.08, 0x9ec5e8, -3.2, 1.55, -5.78, { collide: false, roughness: 0.2, metalness: 0.1 });
    box(1.8, 1.2, 0.08, 0x9ec5e8, 2.8, 1.55, -5.78, { collide: false, roughness: 0.2, metalness: 0.1 });

    // Sofa
    box(2.8, 0.45, 1.0, 0x3d405b, -1.8, 0.28, -3.6);
    box(2.8, 0.35, 0.22, 0x2f3146, -1.8, 0.78, -4.0);
    box(0.22, 0.55, 1.0, 0x2f3146, -3.1, 0.55, -3.6);
    box(0.22, 0.55, 1.0, 0x2f3146, -0.5, 0.55, -3.6);

    // Coffee table
    box(1.2, 0.08, 0.7, 0x6f4e37, -1.6, 0.42, -2.3, { roughness: 0.4 });
    box(0.08, 0.4, 0.08, 0x4a3424, -2.05, 0.2, -2.55, { collide: false });
    box(0.08, 0.4, 0.08, 0x4a3424, -1.15, 0.2, -2.55, { collide: false });
    box(0.08, 0.4, 0.08, 0x4a3424, -2.05, 0.2, -2.05, { collide: false });
    box(0.08, 0.4, 0.08, 0x4a3424, -1.15, 0.2, -2.05, { collide: false });

    // TV unit + TV
    box(2.4, 0.45, 0.55, 0x2b2d42, 2.4, 0.28, -4.8);
    const tvFrame = box(1.7, 1.0, 0.08, 0x111111, 2.4, 1.15, -4.95, { roughness: 0.35, metalness: 0.4 });
    tvScreenMat = mat(0x101018, { roughness: 0.35, emissive: 0x000000, emissiveIntensity: 0 });
    const screen = new THREE.Mesh(new THREE.PlaneGeometry(1.52, 0.86), tvScreenMat);
    screen.position.set(2.4, 1.15, -4.905);
    scene.add(screen);
    tvFrame.userData = { kind: "tv", label: "Televizyon" };
    interactives.push(tvFrame);

    // Sideboard / shelves
    box(1.6, 1.4, 0.4, 0x8d6e63, 5.4, 0.7, -2.8);
    box(0.9, 0.9, 0.35, 0x795548, -5.5, 0.5, -3.5);

    // Kitchen counter area (behind partition)
    box(3.2, 0.9, 0.7, 0xd7ccc8, -4.2, 0.45, 3.6);
    box(3.2, 0.05, 0.75, 0x455a64, -4.2, 0.93, 3.6, { metalness: 0.25, roughness: 0.4 });
    box(0.55, 0.08, 0.45, 0xb0bec5, -3.3, 0.99, 3.55, { collide: false, metalness: 0.5 });

    // Bed-ish lounge chair
    box(0.9, 0.4, 0.9, 0x5c677d, 4.3, 0.25, 0.1);
    box(0.9, 0.55, 0.18, 0x4a5568, 4.3, 0.7, -0.3);

    // Ceiling light fixture
    const ceilLight = new THREE.Mesh(
      new THREE.CylinderGeometry(0.25, 0.35, 0.12, 16),
      mat(0xf5f5f5, { emissive: 0xfff3c4, emissiveIntensity: 0.35 })
    );
    ceilLight.position.set(0, 2.55, -1.5);
    scene.add(ceilLight);
    const roomLight = new THREE.PointLight(0xfff5e0, 0.65, 12);
    roomLight.position.set(0, 2.4, -1.5);
    scene.add(roomLight);
  }

  function addPickup(kind, label, color, w, h, d, x, y, z) {
    const mesh = box(w, h, d, color, x, y, z, { collide: false, roughness: 0.55 });
    mesh.userData = {
      kind: "pickup",
      item: kind,
      label,
      home: new THREE.Vector3(x, y, z),
      size: { w, h, d },
    };
    interactives.push(mesh);
    return mesh;
  }

  function makePickups() {
    addPickup("kumanda", "Kumanda", 0x222831, 0.28, 0.05, 0.1, -1.45, 0.5, -2.25);
    addPickup("vazo", "Vazo", 0xc9184a, 0.18, 0.32, 0.18, 5.3, 1.55, -2.8);
    addPickup("yastik", "Yastık", 0xe9c46a, 0.45, 0.18, 0.35, -1.2, 0.58, -3.55);
    addPickup("kitap", "Kitap", 0x264653, 0.24, 0.05, 0.18, -1.8, 0.5, -2.15);
    addPickup("lamba", "Masa Lambası", 0xf4a261, 0.2, 0.4, 0.2, -5.4, 1.15, -3.5);
    addPickup("kupa", "Kupa", 0xffffff, 0.12, 0.14, 0.12, -3.8, 1.05, 3.45);
    addPickup("tablo", "Küçük Tablo", 0x1d3557, 0.35, 0.28, 0.04, 5.2, 1.9, -2.8);
  }

  function setTv(on) {
    tvOn = on;
    if (!tvScreenMat) return;
    if (on) {
      tvScreenMat.color.setHex(0x1b4332);
      tvScreenMat.emissive.setHex(0x2d6a4f);
      tvScreenMat.emissiveIntensity = 0.85;
    } else {
      tvScreenMat.color.setHex(0x101018);
      tvScreenMat.emissive.setHex(0x000000);
      tvScreenMat.emissiveIntensity = 0;
    }
  }

  function setDoor(open) {
    doorOpen = open;
    if (!doorMesh) return;
    if (open) {
      doorMesh.rotation.y = -Math.PI / 2.05;
      doorMesh.position.set(-0.9, 1.075, 1.2);
    } else {
      doorMesh.rotation.y = 0;
      doorMesh.position.set(0, 1.075, 1.2);
    }
    const doorCol = colliders.find((c) => c.dynamic === "door");
    if (doorCol) {
      doorCol.solid = !open;
      if (open) {
        doorCol.minX = -1.85; doorCol.maxX = -0.85; doorCol.minZ = 0.25; doorCol.maxZ = 1.25;
      } else {
        doorCol.minX = -0.95; doorCol.maxX = 0.95; doorCol.minZ = 1.05; doorCol.maxZ = 1.35;
      }
    }
  }

  function getFocus() {
    const origin = camera.position.clone();
    const dir = new THREE.Vector3(0, 0, -1).applyQuaternion(camera.quaternion);
    const ray = new THREE.Raycaster(origin, dir, 0.1, INTERACT_DIST);
    const hits = ray.intersectObjects(interactives, false);
    if (!hits.length) return null;
    const obj = hits[0].object;
    if (heldObject && obj === heldObject) return null;
    return obj;
  }

  function updatePrompt() {
    focused = getFocus();
    if (!focused) {
      promptEl.classList.remove("show");
      promptEl.textContent = "";
      return;
    }
    const kind = focused.userData.kind;
    let text = focused.userData.label || "Eşya";
    if (kind === "tv") text = tvOn ? "TV kapat" : "TV aç";
    if (kind === "door") text = doorOpen ? "Kapıyı kapat" : "Kapıyı aç";
    if (kind === "pickup") text = heldObject ? "Önce eldekini bırak" : `${text} — al`;
    promptEl.textContent = text;
    promptEl.classList.add("show");
  }

  function interact() {
    focused = getFocus();
    if (!focused) return;
    const kind = focused.userData.kind;
    if (kind === "tv") {
      setTv(!tvOn);
      return;
    }
    if (kind === "door") {
      setDoor(!doorOpen);
      return;
    }
    if (kind === "pickup" && !heldObject) {
      heldObject = focused;
      heldObject.visible = false;
      heldEl.style.display = "block";
      heldEl.textContent = `Elde: ${heldObject.userData.label}`;
      updateStatus();
    }
  }

  function dropHeld() {
    if (!heldObject) return;
    const forward = new THREE.Vector3(0, 0, -1).applyQuaternion(camera.quaternion);
    forward.y = 0;
    forward.normalize();
    const dropPos = camera.position.clone().add(forward.multiplyScalar(1.15));
    dropPos.y = heldObject.userData.size.h / 2 + 0.02;
    // Keep inside room bounds
    dropPos.x = Math.max(-6.4, Math.min(6.4, dropPos.x));
    dropPos.z = Math.max(-5.4, Math.min(5.4, dropPos.z));
    heldObject.position.copy(dropPos);
    heldObject.visible = true;
    heldObject = null;
    heldEl.style.display = "none";
    updateStatus();
  }

  function updateStatus() {
    const room = camera.position.z > 1.2 ? "Mutfak / Koridor" : "Salon";
    const held = heldObject ? 1 : 0;
    const tv = tvOn ? "TV açık" : "TV kapalı";
    const door = doorOpen ? "Kapı açık" : "Kapı kapalı";
    statusEl.innerHTML = `${room}<br>${tv} • ${door}<br>Eşya elde: ${held}`;
  }

  function blocked(nx, nz) {
    const r = 0.28;
    if (nx < -6.55 || nx > 6.55 || nz < -5.55 || nz > 5.55) return true;
    for (const c of colliders) {
      if (!c.solid) continue;
      if (nx + r > c.minX && nx - r < c.maxX && nz + r > c.minZ && nz - r < c.maxZ) return true;
    }
    return false;
  }

  function movePlayer(dt) {
    const forward = new THREE.Vector3(-Math.sin(yawPitch.yaw), 0, -Math.cos(yawPitch.yaw));
    const right = new THREE.Vector3(Math.cos(yawPitch.yaw), 0, -Math.sin(yawPitch.yaw));
    velocity.set(0, 0, 0);
    velocity.addScaledVector(forward, keys.forward);
    velocity.addScaledVector(right, keys.strafe);
    if (velocity.lengthSq() > 1) velocity.normalize();
    const step = MOVE_SPEED * dt;
    const px = camera.position.x;
    const pz = camera.position.z;
    const nx = px + velocity.x * step;
    const nz = pz + velocity.z * step;
    if (!blocked(nx, pz)) camera.position.x = nx;
    if (!blocked(camera.position.x, nz)) camera.position.z = nz;
    camera.position.y = PLAYER_HEIGHT;

    yawPitch.pitch = Math.max(-1.2, Math.min(1.2, yawPitch.pitch - lookStick.y * 1.6 * dt));
    yawPitch.yaw -= lookStick.x * 1.8 * dt;
    camera.rotation.order = "YXZ";
    camera.rotation.y = yawPitch.yaw;
    camera.rotation.x = yawPitch.pitch;
  }

  function bindPad(padId, knobId, onMove, onEnd) {
    const pad = document.getElementById(padId);
    const knob = document.getElementById(knobId);
    let activeId = null;

    function handle(clientX, clientY) {
      const rect = pad.getBoundingClientRect();
      const cx = rect.left + rect.width / 2;
      const cy = rect.top + rect.height / 2;
      let dx = clientX - cx;
      let dy = clientY - cy;
      const max = rect.width * 0.38;
      const len = Math.hypot(dx, dy) || 1;
      if (len > max) {
        dx = (dx / len) * max;
        dy = (dy / len) * max;
      }
      knob.style.transform = `translate(${dx}px, ${dy}px)`;
      onMove(dx / max, dy / max);
    }

    function end() {
      activeId = null;
      knob.style.transform = "translate(0,0)";
      onEnd();
    }

    pad.addEventListener("touchstart", (e) => {
      e.preventDefault();
      const t = e.changedTouches[0];
      activeId = t.identifier;
      handle(t.clientX, t.clientY);
    }, { passive: false });

    pad.addEventListener("touchmove", (e) => {
      e.preventDefault();
      for (const t of e.changedTouches) {
        if (t.identifier === activeId) handle(t.clientX, t.clientY);
      }
    }, { passive: false });

    pad.addEventListener("touchend", (e) => {
      for (const t of e.changedTouches) {
        if (t.identifier === activeId) end();
      }
    });
    pad.addEventListener("touchcancel", end);

    // Mouse for desktop testing
    let mouseDown = false;
    pad.addEventListener("mousedown", (e) => {
      mouseDown = true;
      handle(e.clientX, e.clientY);
    });
    window.addEventListener("mousemove", (e) => {
      if (mouseDown) handle(e.clientX, e.clientY);
    });
    window.addEventListener("mouseup", () => {
      if (mouseDown) {
        mouseDown = false;
        end();
      }
    });
  }

  bindPad("move-pad", "move-knob", (x, y) => {
    keys.strafe = x;
    keys.forward = -y;
  }, () => {
    keys.strafe = 0;
    keys.forward = 0;
  });

  bindPad("look-pad", "look-knob", (x, y) => {
    lookStick.x = x;
    lookStick.y = y;
  }, () => {
    lookStick.x = 0;
    lookStick.y = 0;
  });

  document.getElementById("btn-interact").addEventListener("click", (e) => {
    e.preventDefault();
    interact();
  });
  document.getElementById("btn-drop").addEventListener("click", (e) => {
    e.preventDefault();
    dropHeld();
  });

  window.addEventListener("keydown", (e) => {
    if (e.code === "KeyW" || e.code === "ArrowUp") keys.forward = 1;
    if (e.code === "KeyS" || e.code === "ArrowDown") keys.forward = -1;
    if (e.code === "KeyA" || e.code === "ArrowLeft") keys.strafe = -1;
    if (e.code === "KeyD" || e.code === "ArrowRight") keys.strafe = 1;
    if (e.code === "KeyE" || e.code === "Space") interact();
    if (e.code === "KeyQ" || e.code === "KeyG") dropHeld();
  });
  window.addEventListener("keyup", (e) => {
    if (e.code === "KeyW" || e.code === "ArrowUp" || e.code === "KeyS" || e.code === "ArrowDown") keys.forward = 0;
    if (e.code === "KeyA" || e.code === "ArrowLeft" || e.code === "KeyD" || e.code === "ArrowRight") keys.strafe = 0;
  });

  // Desktop look with pointer lock / drag on canvas
  let dragging = false;
  let lastX = 0;
  let lastY = 0;
  renderer.domElement.addEventListener("mousedown", (e) => {
    dragging = true;
    lastX = e.clientX;
    lastY = e.clientY;
  });
  window.addEventListener("mouseup", () => { dragging = false; });
  window.addEventListener("mousemove", (e) => {
    if (!dragging || !started) return;
    yawPitch.yaw -= (e.clientX - lastX) * LOOK_SENS * 1.4;
    yawPitch.pitch -= (e.clientY - lastY) * LOOK_SENS * 1.4;
    lastX = e.clientX;
    lastY = e.clientY;
  });

  window.addEventListener("resize", () => {
    camera.aspect = window.innerWidth / window.innerHeight;
    camera.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  });

  startBtn.addEventListener("click", () => {
    titleOverlay.style.display = "none";
    started = true;
  });

  makeRoom();
  makePickups();
  setTv(false);
  setDoor(false);
  updateStatus();

  function animate() {
    requestAnimationFrame(animate);
    const dt = Math.min(clock.getDelta(), 0.05);
    if (started) {
      movePlayer(dt);
      updatePrompt();
      if (tvOn) {
        tvNoise += dt;
        const pulse = 0.7 + Math.sin(tvNoise * 6) * 0.15;
        tvScreenMat.emissiveIntensity = pulse;
        // Fake channel flicker color
        const c = (Math.sin(tvNoise * 3.1) * 0.5 + 0.5);
        tvScreenMat.emissive.setRGB(0.1 + c * 0.15, 0.35 + c * 0.2, 0.25);
      }
      updateStatus();
    }
    renderer.render(scene, camera);
  }
  animate();
})();
