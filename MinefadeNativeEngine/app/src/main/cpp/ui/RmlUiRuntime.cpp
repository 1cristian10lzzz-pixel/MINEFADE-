#include "RmlUiRuntime.h"

#include <android/log.h>

#include <algorithm>
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
#topbar { position: absolute; top: 7dp; left: 7dp; right: 7dp; height: 36dp; display: flex; align-items: center; justify-content: flex-end; pointer-events: none; }
#topbar button { pointer-events: auto; }
#brand { display: none; }
#stats { color: #edf2f5; font-size: 10dp; background: transparent; padding: 4dp 7dp; border: none; border-radius: 7dp; text-shadow: none; }
.actions { display: flex; flex-direction: row; gap: 4dp; }
button { color: #ffffff; background: #1b2a3733; border: 1dp #b9c9d855; border-radius: 5dp; padding: 5dp 7dp; font-size: 12dp; }
button:hover { background: #365a77; }
button:active { background: #47789b; }
#crosshair { position: absolute; left: 50%; top: 50%; width: 12dp; height: 12dp; margin-left: -6dp; margin-top: -6dp; pointer-events: none; }
#crosshair-v { position: absolute; left: 5dp; top: 0dp; width: 2dp; height: 12dp; background: #ffffffcc; }
#crosshair-h { position: absolute; left: 0dp; top: 5dp; width: 12dp; height: 2dp; background: #ffffffcc; }
#hint { display: none; }
.panel { position: absolute; left: 50%; top: 50%; width: 310dp; margin-left: -155dp; margin-top: -112dp; padding: 18dp; background: #111d2af2; border: 1dp #607b91; border-radius: 12dp; display: none; }
.panel h1 { font-size: 20dp; margin: 0dp 0dp 10dp 0dp; color: #ffffff; }
.panel p { font-size: 12dp; color: #c8d3db; margin: 3dp 0dp 12dp 0dp; }
.panel .actions { flex-wrap: wrap; }
#quickbar { position: absolute; bottom: 12dp; left: 50%; margin-left: -143dp; display: flex; flex-direction: row; gap: 3dp; }
.slot { position: relative; width: 29dp; min-width: 29dp; height: 32dp; text-align: center; padding: 2dp; display: flex; align-items: center; justify-content: center; background: #172b3a66; border: 1dp #d8e6ee66; border-radius: 7dp; color: #ffffff; font-size: 8dp; }
.slot img { width: 21dp; height: 21dp; display: block; margin: auto; }
.slot .slot-count { position: absolute; right: 1dp; bottom: 0dp; color: #ffffff; font-size: 7dp; text-shadow: 0dp 1dp 2dp #000000; }
.inventory-row { display: flex; align-items: center; gap: 7dp; color: #e0e8ed; font-size: 12dp; padding: 5dp 2dp; border-bottom: 1dp #43576a; }
.inventory-icon { width: 19dp; height: 19dp; }
#inventory, #pause, #perspective { width: 34dp; height: 34dp; padding: 0dp; text-align: center; background: #172b3a55; border: 1dp #d8e6ee55; border-radius: 9dp; }
#movement-pad { position: absolute; left: 17dp; bottom: 48dp; width: 92dp; height: 92dp; border-radius: 50%; border: 1dp #ffffff44; background: transparent; pointer-events: none; }
#movement-pad-inner { position: absolute; left: 25dp; top: 25dp; width: 42dp; height: 42dp; border-radius: 50%; border: 1dp #d9e9a488; background: #6c9c8c33; }
#movement-pad-cross { position: absolute; left: 45dp; top: 12dp; width: 2dp; height: 68dp; background: #ffffff22; }
#jump { position: absolute; right: 21dp; bottom: 67dp; width: 45dp; height: 45dp; padding: 2dp; text-align: center; border-radius: 50%; background: #31566dcc; border: 1dp #d9e9a4bb; }
#inventory-panel { left: auto; right: 10dp; top: 52dp; width: 205dp; margin: 0dp; }
.inventory-row { color: #e0e8ed; font-size: 12dp; padding: 5dp 2dp; border-bottom: 1dp #43576a; }
#pause-panel { z-index: 10; width: 270dp; margin-left: -135dp; }
#pause-panel .actions { justify-content: center; gap: 8dp; }
#pause-panel button { min-width: 104dp; }
.panel h1, .panel p { display: none; }
#target { position: absolute; left: 50%; top: 57%; margin-left: -75dp; min-width: 150dp; text-align: center; color: #ffffff; font-size: 11dp; text-shadow: 0dp 1dp 3dp #000000; display: none; pointer-events: none; }
#target-progress { width: 84dp; height: 3dp; margin: 4dp auto 0dp auto; background: #ffffff22; }
#target-progress-fill { height: 3dp; width: 0%; background: #ffffff; }
#pickup { position: absolute; right: 12dp; bottom: 54dp; color: #ffffff; font-size: 12dp; text-shadow: 0dp 1dp 3dp #000000; background: transparent; padding: 0dp; pointer-events: none; }

#inventory-panel { z-index: 9; }
</style>
</head>
<body>
<div id="topbar">
  <div class="actions">
    <div id="stats">FPS -- · Chunks --</div>
    <button id="inventory">⋯</button>
    <button id="perspective">◉</button>
    <button id="pause">Ⅱ</button>
  </div>
</div>
<div id="crosshair"><div id="crosshair-v"></div><div id="crosshair-h"></div></div>
<div id="movement-pad"><div id="movement-pad-inner"></div><div id="movement-pad-cross"></div></div>
<div id="hint"></div>
<div id="quickbar">
  <button class="slot" id="slot-0">1</button><button class="slot" id="slot-1">2</button><button class="slot" id="slot-2">3</button>
  <button class="slot" id="slot-3">4</button><button class="slot" id="slot-4">5</button><button class="slot" id="slot-5">6</button>
  <button class="slot" id="slot-6">7</button><button class="slot" id="slot-7">8</button><button class="slot" id="slot-8">9</button>
</div>
<button id="jump">↑</button>
<div id="target"><div id="target-name"></div><div id="target-progress"><div id="target-progress-fill"></div></div></div>
<div id="pickup"></div>
<div id="inventory-panel" class="panel">
  <div id="inventory-items"></div>
  <div class="actions"><button id="inventory-close">Cerrar</button></div>
</div>
<div id="pause-panel" class="panel">
  <div class="actions">
    <button id="resume">Continuar</button>
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
    if(!blockAtlasPixels_.empty())
        renderer_.SetBlockAtlas(blockAtlasPixels_.data(),blockAtlasPixels_.size(),
                                blockAtlasWidth_,blockAtlasHeight_);

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
    std::string hudMarkup = kHudDocument;
    if (!showStats_) {
        const std::string statsRule = "#stats { color:";
        const std::size_t statsPosition = hudMarkup.find(statsRule);
        if (statsPosition != std::string::npos)
            hudMarkup.replace(statsPosition, statsRule.size(), "#stats { display: none; color:");
    }
    if (leftHanded_) {
        const auto replaceStyle = [&hudMarkup](const std::string& from, const std::string& to) {
            const std::size_t position = hudMarkup.find(from);
            if (position != std::string::npos) hudMarkup.replace(position, from.size(), to);
        };
        replaceStyle("left: 17dp; bottom: 48dp;", "right: 17dp; left: auto; bottom: 48dp;");
        replaceStyle("right: 21dp; bottom: 67dp;", "left: 21dp; right: auto; bottom: 67dp;");
    }
    document_ = context_->LoadDocumentFromMemory(hudMarkup, "minefade://hud.rml");
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
    AttachAction("perspective", "perspective");
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

void RmlUiRuntime::SetBlockAtlas(const std::uint8_t* pixels,std::size_t bytes,
                                     int width,int height) {
    if(pixels==nullptr||width<=0||height<=0) {
        blockAtlasPixels_.clear();
        blockAtlasWidth_=blockAtlasHeight_=0;
        return;
    }
    const std::size_t expected=static_cast<std::size_t>(width)*
        static_cast<std::size_t>(height)*4u;
    if(bytes<expected)return;
    blockAtlasPixels_.assign(pixels,pixels+expected);
    blockAtlasWidth_=width;
    blockAtlasHeight_=height;
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

void RmlUiRuntime::SetTargetBlock(const std::string& name, float progress) {
    if (document_ == nullptr) return;
    Rml::Element* target = document_->GetElementById("target");
    Rml::Element* label = document_->GetElementById("target-name");
    Rml::Element* fill = document_->GetElementById("target-progress-fill");
    if (target == nullptr || label == nullptr || fill == nullptr) return;
    if (name.empty()) {
        target->SetProperty("display", "none");
        return;
    }
    label->SetInnerRML(name);
    target->SetProperty("display", "block");
    const float percent = std::clamp(progress, 0.0f, 1.0f) * 100.0f;
    fill->SetProperty("width", std::to_string(percent) + "%");
}

void RmlUiRuntime::SetPickupToast(const std::string& text) {
    if (document_ == nullptr) return;
    if (Rml::Element* element = document_->GetElementById("pickup")) {
        element->SetInnerRML(text);
        element->SetProperty("display", text.empty() ? "none" : "block");
    }
}

void RmlUiRuntime::SetPerspectiveEnabled(bool enabled) {
    if (document_ == nullptr) return;
    if (Rml::Element* element = document_->GetElementById("perspective")) {
        element->SetProperty("background-color", enabled ? "#d9e9a455" : "#1b2a3733");
        element->SetProperty("border", enabled ? "1dp #d9e9a4" : "1dp #b9c9d855");
    }
}

void RmlUiRuntime::SetQuickbarSlot(int slot,const std::string& label,bool selected) {
    if(document_==nullptr||slot<0||slot>=9)return;
    const std::string id="slot-"+std::to_string(slot);
    if(Rml::Element* element=document_->GetElementById(id)) {
        element->SetInnerRML(label);
        element->SetProperty("border",selected?"2dp #d9e9a4":"1dp #ffffff33");
        element->SetProperty("background-color",selected?"#d9e9a422":"#10192322");
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
