#include "RmlUiRuntime.h"

#include <android/log.h>

#include <chrono>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace minefade {
namespace {
constexpr const char* kTag = "MinefadeRmlUi";
const auto kStartTime = std::chrono::steady_clock::now();

constexpr char kHudDocument[] = R"RML(
<rml>
<head>
<title>Minefade HUD</title>
<style>
body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; font-family: Roboto; color: #f3f6f8; }
#topbar { position: absolute; top: 12dp; left: 12dp; right: 12dp; height: 42dp; display: flex; align-items: center; justify-content: space-between; pointer-events: none; }
#topbar button { pointer-events: auto; }
#brand { color: #ffffff; font-size: 18dp; font-weight: bold; text-shadow: 0dp 2dp 4dp #000000; }
#stats { color: #edf2f5; font-size: 12dp; background: #101923cc; padding: 7dp 11dp; border-radius: 8dp; }
.actions { display: flex; flex-direction: row; gap: 8dp; }
button { color: #ffffff; background: #22384cdd; border: 1dp #668098; border-radius: 8dp; padding: 8dp 13dp; font-size: 13dp; }
button:hover { background: #365a77; }
button:active { background: #47789b; }
#crosshair { position: absolute; left: 50%; top: 50%; width: 12dp; height: 12dp; margin-left: -6dp; margin-top: -6dp; pointer-events: none; }
#crosshair-v { position: absolute; left: 5dp; top: 0dp; width: 2dp; height: 12dp; background: #ffffffcc; }
#crosshair-h { position: absolute; left: 0dp; top: 5dp; width: 12dp; height: 2dp; background: #ffffffcc; }
#hint { position: absolute; bottom: 12dp; left: 50%; margin-left: -110dp; width: 220dp; text-align: center; color: #ffffff; font-size: 11dp; background: #10192399; border-radius: 8dp; padding: 6dp; pointer-events: none; }
.panel { position: absolute; left: 50%; top: 50%; width: 310dp; margin-left: -155dp; margin-top: -112dp; padding: 18dp; background: #111d2af2; border: 1dp #607b91; border-radius: 12dp; display: none; }
.panel h1 { font-size: 20dp; margin: 0dp 0dp 10dp 0dp; color: #ffffff; }
.panel p { font-size: 12dp; color: #c8d3db; margin: 3dp 0dp 12dp 0dp; }
.panel .actions { flex-wrap: wrap; }
#quickbar { position: absolute; bottom: 16dp; left: 50%; margin-left: -146dp; display: flex; flex-direction: row; gap: 4dp; }
.slot { min-width: 27dp; height: 27dp; text-align: center; padding: 5dp 4dp; background: #101923aa; border: 1dp #75889866; border-radius: 5dp; color: #ffffff; font-size: 11dp; }
#jump { position: absolute; right: 20dp; bottom: 76dp; width: 58dp; height: 42dp; padding: 7dp; }
#inventory-panel { left: auto; right: 14dp; top: 68dp; width: 215dp; margin: 0dp; }
.inventory-row { color: #e0e8ed; font-size: 12dp; padding: 5dp 2dp; border-bottom: 1dp #43576a; }
#pause-panel { z-index: 10; }
#inventory-panel { z-index: 9; }
</style>
</head>
<body>
<div id="topbar">
  <div id="brand">MINEFADE <span style="color:#86d18b">NATIVE</span></div>
  <div class="actions">
    <div id="stats">FPS -- · Chunks -- · Día</div>
    <button id="inventory">Mochila</button>
    <button id="pause">Pausa</button>
  </div>
</div>
<div id="crosshair"><div id="crosshair-v"></div><div id="crosshair-h"></div></div>
<div id="hint">Arrastra a la izquierda para moverte · a la derecha para mirar</div>
<div id="quickbar">
  <button class="slot" id="slot-0">1</button><button class="slot" id="slot-1">2</button><button class="slot" id="slot-2">3</button>
  <button class="slot" id="slot-3">4</button><button class="slot" id="slot-4">5</button><button class="slot" id="slot-5">6</button>
  <button class="slot" id="slot-6">7</button><button class="slot" id="slot-7">8</button><button class="slot" id="slot-8">9</button>
</div>
<button id="jump">Saltar</button>
<div id="inventory-panel" class="panel">
  <h1>Inventario</h1>
  <p>Barra rápida · 9 espacios</p>
  <div id="inventory-items"></div>
  <div class="actions"><button id="inventory-close">Cerrar</button></div>
</div>
<div id="pause-panel" class="panel">
  <h1>Partida pausada</h1>
  <p>El mundo se guarda en el almacenamiento privado de la aplicación.</p>
  <div class="actions">
    <button id="resume">Continuar</button>
    <button id="save">Guardar mundo</button>
    <button id="save-exit">Guardar y salir</button>
  </div>
</div>
</body>
</rml>
)RML";
}

double RmlUiRuntime::AndroidSystemInterface::GetElapsedTime() {
    const auto elapsed = std::chrono::steady_clock::now() - kStartTime;
    return std::chrono::duration<double>(elapsed).count();
}

bool RmlUiRuntime::AndroidSystemInterface::LogMessage(Rml::Log::Type type, const Rml::String& message) {
    const int priority = type == Rml::Log::LT_ERROR ? ANDROID_LOG_ERROR :
                        type == Rml::Log::LT_WARNING ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_print(priority, kTag, "%s", message.c_str());
    return true;
}

void RmlUiRuntime::ActionListener::ProcessEvent(Rml::Event& event) {
    (void)event;
    if (callback_) callback_(action_);
}

bool RmlUiRuntime::Initialise(int width, int height, float displayDensity, ActionCallback callback) {
    Shutdown();
    width_ = width > 0 ? width : 1;
    height_ = height > 0 ? height : 1;
    callback_ = std::move(callback);

    if (!renderer_.Initialise()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not initialise the OpenGL ES RmlUi renderer");
        return false;
    }

    Rml::SetSystemInterface(&system_);
    Rml::SetRenderInterface(&renderer_);
    if (!Rml::Initialise()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Rml::Initialise failed");
        renderer_.Shutdown();
        return false;
    }
    rmlInitialised_ = true;

    fontData_.clear();
    const char* fontPaths[] = {"/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf"};
    for (const char* path : fontPaths) {
        std::ifstream font(path, std::ios::binary);
        if (!font) continue;
        fontData_.assign(std::istreambuf_iterator<char>(font), std::istreambuf_iterator<char>());
        if (!fontData_.empty()) break;
    }
    if (!fontData_.empty()) {
        const Rml::Span<const Rml::byte> fontSpan(fontData_.data(), fontData_.size());
        if (!Rml::LoadFontFace(fontSpan, "Roboto", Rml::Style::FontStyle::Normal,
                               Rml::Style::FontWeight::Normal, true)) {
            __android_log_print(ANDROID_LOG_WARN, kTag, "The Android system font could not be registered with RmlUi");
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "No readable Android system font was found; RmlUi text may be unavailable");
    }

    context_ = Rml::CreateContext("minefade_hud", {width_, height_}, &renderer_);
    if (context_ == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not create the Minefade RmlUi context");
        Rml::Shutdown();
        rmlInitialised_ = false;
        renderer_.Shutdown();
        return false;
    }
    context_->SetDensityIndependentPixelRatio(displayDensity > 0.25f ? displayDensity : 1.0f);
    document_ = context_->LoadDocumentFromMemory(kHudDocument, "minefade://hud.rml");
    if (document_ == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not load the Minefade HUD document");
        Rml::RemoveContext("minefade_hud");
        context_ = nullptr;
        Rml::Shutdown();
        rmlInitialised_ = false;
        renderer_.Shutdown();
        return false;
    }

    AttachAction("inventory", "inventory");
    AttachAction("inventory-close", "inventory-close");
    AttachAction("jump", "jump");
    for (int slot = 0; slot < 9; ++slot) {
        const std::string id = "slot-" + std::to_string(slot);
        AttachAction(id.c_str(), id.c_str());
    }
    AttachAction("pause", "pause");
    AttachAction("resume", "resume");
    AttachAction("save", "save");
    AttachAction("save-exit", "save-exit");
    document_->Show();
    context_->Update();
    ready_ = true;
    __android_log_print(ANDROID_LOG_INFO, kTag, "RmlUi %s initialised with OpenGL ES backend", Rml::GetVersion().c_str());
    return true;
}

void RmlUiRuntime::AttachAction(const char* elementId, const char* action) {
    if (document_ == nullptr) return;
    Rml::Element* element = document_->GetElementById(elementId);
    if (element == nullptr) return;
    listeners_.push_back(std::make_unique<ActionListener>(callback_, action));
    element->AddEventListener("click", listeners_.back().get());
}

void RmlUiRuntime::Shutdown() {
    ready_ = false;
    // Context/document destruction invokes listener detach callbacks, so listeners must outlive it.
    document_ = nullptr;
    if (context_ != nullptr) {
        Rml::RemoveContext("minefade_hud");
        context_ = nullptr;
    }
    listeners_.clear();
    // RmlUi requires its contexts to be destroyed before Shutdown and the render interface to outlive it.
    if (rmlInitialised_) Rml::Shutdown();
    rmlInitialised_ = false;
    fontData_.clear();
    mouseCaptured_ = false;
    renderer_.Shutdown();
}

void RmlUiRuntime::Resize(int width, int height) {
    width_ = width > 0 ? width : 1;
    height_ = height > 0 ? height : 1;
    renderer_.SetViewport(width_, height_);
    if (context_ != nullptr) context_->SetDimensions({width_, height_});
}

bool RmlUiRuntime::Touch(int action, float x, float y) {
    if (!ready_ || context_ == nullptr) return true;
    const int px = static_cast<int>(x);
    const int py = static_cast<int>(y);
    const bool outside = context_->ProcessMouseMove(px, py, 0);
    if (action == 0) {
        const bool buttonOutside = context_->ProcessMouseButtonDown(0, 0);
        mouseCaptured_ = !(outside && buttonOutside);
        return !mouseCaptured_;
    }
    if (action == 1 || action == 3) {
        const bool buttonOutside = context_->ProcessMouseButtonUp(0, 0);
        const bool consumed = mouseCaptured_ || !outside || !buttonOutside;
        mouseCaptured_ = false;
        return !consumed;
    }
    return !mouseCaptured_ && outside;
}

void RmlUiRuntime::UpdateAndRender() {
    if (!ready_ || context_ == nullptr) return;
    context_->Update();
    context_->Render();
}

void RmlUiRuntime::SetStatus(const std::string& status) {
    if (document_ == nullptr) return;
    if (Rml::Element* element = document_->GetElementById("stats")) element->SetInnerRML(status);
}

void RmlUiRuntime::SetQuickbarSlot(int slot, const std::string& label, bool selected) {
    if (document_ == nullptr || slot < 0 || slot >= 9) return;
    const std::string id = "slot-" + std::to_string(slot);
    if (Rml::Element* element = document_->GetElementById(id)) {
        element->SetInnerRML(label);
        element->SetProperty("border", selected ? "2dp #d9e9a4" : "1dp #75889866");
        element->SetProperty("background-color", selected ? "#405b43dd" : "#101923aa");
    }
}

void RmlUiRuntime::SetInventoryContents(const std::string& markup) {
    if (document_ == nullptr) return;
    if (Rml::Element* element = document_->GetElementById("inventory-items")) element->SetInnerRML(markup);
}

void RmlUiRuntime::SetPanelVisible(const char* elementId, bool visible) {
    if (document_ == nullptr || elementId == nullptr) return;
    if (Rml::Element* element = document_->GetElementById(elementId))
        element->SetProperty("display", visible ? "block" : "none");
}

} // namespace minefade
