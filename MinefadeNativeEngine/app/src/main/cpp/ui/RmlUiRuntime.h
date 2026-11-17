#pragma once

#include "RmlUiOpenGLES.h"

#include <RmlUi/Core.h>
#include <RmlUi/Core/FileInterface.h>
#include <cstddef>
#include <string>
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
    bool IsInMovementPad(float x, float y) const;
    void UpdateAndRender();
    void SetStatus(const std::string& status);
    void SetLeftHanded(bool enabled) { leftHanded_ = enabled; }
    void SetControlMode(int mode);
    void SetStatusVisible(bool visible) { showStats_ = visible; }
    void SetTargetBlock(const std::string& name, float progress);
    void SetPickupToast(const std::string& text);
    void SetSelectedItem(const std::string& markup);
    void SetPerspectiveEnabled(bool enabled);
    void SetCrouchEnabled(bool enabled);
    void SetChestContents(const std::string& markup);
    void SetChatMessage(const std::string& message);
    void SetMinimap(const std::string& gridMarkup, const std::string& coordinates);
    void SetChestButtonVisible(bool visible);
    void SetQuickbarSlot(int slot, const std::string& label, bool selected);
    void SetInventoryContents(const std::string& markup);
    void SetCraftingContents(const std::string& markup);
    void SetInventoryCatalogContents(const std::string& markup);
    void SetEquipmentContents(const std::string& markup);
    void SetPanelVisible(const char* elementId, bool visible);
    bool IsReady() const { return ready_; }
    const std::string& LastError() const { return lastError_; }

private:
    class AndroidSystemInterface final : public Rml::SystemInterface {
    public:
        double GetElapsedTime() override;
        bool LogMessage(Rml::Log::Type type, const Rml::String& message) override;
    };

    // The native HUD is loaded from memory, so it installs an explicit
    // read-only file interface instead of relying on an optional stdio backend.
    class MemoryOnlyFileInterface final : public Rml::FileInterface {
    public:
        Rml::FileHandle Open(const Rml::String& path) override;
        void Close(Rml::FileHandle file) override;
        std::size_t Read(void* buffer, std::size_t size, Rml::FileHandle file) override;
        bool Seek(Rml::FileHandle file, long offset, int origin) override;
        std::size_t Tell(Rml::FileHandle file) override;
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
    MemoryOnlyFileInterface fileInterface_;
    Rml::Context* context_{nullptr};
    Rml::ElementDocument* document_{nullptr};
    ActionCallback callback_;
    std::vector<std::unique_ptr<ActionListener>> listeners_;
    std::vector<std::unique_ptr<ActionListener>> inventoryListeners_;
    std::vector<std::unique_ptr<ActionListener>> craftingListeners_;
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
    int controlMode_{0};
    bool showStats_{true};
    std::string lastError_;
};

} // namespace minefade
