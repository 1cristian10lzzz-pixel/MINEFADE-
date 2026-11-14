#pragma once

#include "RmlUiOpenGLES.h"

#include <RmlUi/Core.h>
#include <functional>
#include <cstdint>
#include <cstddef>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace minefade {

class RmlUiRuntime {
public:
    using ActionCallback = std::function<void(const std::string&)>;

    bool Initialise(int width, int height, float displayDensity, ActionCallback callback);
    void Shutdown();
    void Resize(int width, int height);
    void SetBlockAtlas(const std::uint8_t* pixels, std::size_t bytes, int width, int height);
    bool Touch(int action, float x, float y);
    void UpdateAndRender();
    void SetStatus(const std::string& status);
    void SetLeftHanded(bool enabled) { leftHanded_ = enabled; }
    void SetStatusVisible(bool visible) { showStats_ = visible; }
    void SetTargetBlock(const std::string& name, float progress);
    void SetPickupToast(const std::string& text);
    void SetPerspectiveEnabled(bool enabled);
    void SetCrouchEnabled(bool enabled);
    void SetQuickbarSlot(int slot, const std::string& label, bool selected);
    void SetInventoryContents(const std::string& markup);
    void SetPanelVisible(const char* elementId, bool visible);
    bool IsReady() const { return ready_; }

private:
    class AndroidSystemInterface final : public Rml::SystemInterface {
    public:
        double GetElapsedTime() override;
        bool LogMessage(Rml::Log::Type type, const Rml::String& message) override;
    };

    class ActionListener final : public Rml::EventListener {
    public:
        ActionListener(ActionCallback callback, std::string action)
            : callback_(std::move(callback)), action_(std::move(action)) {}
        void ProcessEvent(Rml::Event& event) override;
    private:
        ActionCallback callback_;
        std::string action_;
    };

    void AttachAction(const char* elementId, const char* action);

    RmlUiOpenGLES renderer_;
    AndroidSystemInterface system_;
    Rml::Context* context_{nullptr};
    Rml::ElementDocument* document_{nullptr};
    ActionCallback callback_;
    std::vector<std::unique_ptr<ActionListener>> listeners_;
    std::vector<Rml::byte> fontData_;
    std::vector<std::uint8_t> blockAtlasPixels_;
    int blockAtlasWidth_{0};
    int blockAtlasHeight_{0};
    int width_{1};
    int height_{1};
    float density_{1.0f};
    bool ready_{false};
    bool rmlInitialised_{false};
    bool mouseCaptured_{false};
    bool leftHanded_{false};
    bool showStats_{true};
};

} // namespace minefade
