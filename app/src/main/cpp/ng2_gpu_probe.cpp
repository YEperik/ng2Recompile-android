#include "ng2_gpu_probe.h"

#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <vector>

namespace ng2 {
namespace {

// The features src/ui/vulkan/vulkan_device.cpp of the SDK looks at.
struct FeatureRow {
  const char* name;
  VkBool32 VkPhysicalDeviceFeatures::*member;
};
const FeatureRow kFeatures[] = {
    {"independentBlend", &VkPhysicalDeviceFeatures::independentBlend},
    {"fragmentStoresAndAtomics", &VkPhysicalDeviceFeatures::fragmentStoresAndAtomics},
    {"vertexPipelineStoresAndAtomics", &VkPhysicalDeviceFeatures::vertexPipelineStoresAndAtomics},
    {"geometryShader", &VkPhysicalDeviceFeatures::geometryShader},
    {"fillModeNonSolid", &VkPhysicalDeviceFeatures::fillModeNonSolid},
    {"sparseBinding", &VkPhysicalDeviceFeatures::sparseBinding},
    {"shaderStorageImageExtendedFormats",
     &VkPhysicalDeviceFeatures::shaderStorageImageExtendedFormats},
};

void Line(std::string& out, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
void Line(std::string& out, const char* fmt, ...) {
  char buf[256];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof buf, fmt, ap);
  va_end(ap);
  out += buf;
  out += '\n';
}

const char* TypeName(VkPhysicalDeviceType t) {
  switch (t) {
    case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: return "integrated";
    case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: return "discrete";
    case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU: return "virtual";
    case VK_PHYSICAL_DEVICE_TYPE_CPU: return "cpu";
    default: return "other";
  }
}

}  // namespace

std::string ProbeVulkan() {
  std::string out;
  void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
  if (!lib) {
    Line(out, "loaded=0");
    Line(out, "error=libvulkan.so tidak ada (perangkat tanpa Vulkan)");
    return out;
  }
  auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
  if (!gipa) {
    Line(out, "loaded=0");
    Line(out, "error=vkGetInstanceProcAddr tidak ditemukan");
    return out;
  }
  auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
  auto enumVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
      gipa(nullptr, "vkEnumerateInstanceVersion"));  // null on a Vulkan 1.0 loader
  if (!createInstance) {
    Line(out, "loaded=0");
    Line(out, "error=vkCreateInstance tidak ditemukan");
    return out;
  }

  uint32_t inst_ver = VK_API_VERSION_1_0;
  if (enumVersion) enumVersion(&inst_ver);

  VkApplicationInfo app{};
  app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
  app.pApplicationName = "ng2-probe";
  app.apiVersion = VK_API_VERSION_1_0;  // 1.0 is always accepted
  VkInstanceCreateInfo ci{};
  ci.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
  ci.pApplicationInfo = &app;
  VkInstance inst = VK_NULL_HANDLE;
  VkResult r = createInstance(&ci, nullptr, &inst);
  if (r != VK_SUCCESS || !inst) {
    Line(out, "loaded=0");
    Line(out, "error=vkCreateInstance gagal (kode %d)", static_cast<int>(r));
    return out;
  }
  Line(out, "loaded=1");
  Line(out, "instance=%u.%u", VK_VERSION_MAJOR(inst_ver), VK_VERSION_MINOR(inst_ver));

#define LOAD(name) auto name = reinterpret_cast<PFN_##name>(gipa(inst, #name))
  LOAD(vkDestroyInstance);
  LOAD(vkEnumeratePhysicalDevices);
  LOAD(vkGetPhysicalDeviceProperties);
  LOAD(vkGetPhysicalDeviceFeatures);
  LOAD(vkGetPhysicalDeviceMemoryProperties);
  LOAD(vkGetPhysicalDeviceQueueFamilyProperties);
  LOAD(vkEnumerateDeviceExtensionProperties);
#undef LOAD
  if (!vkEnumeratePhysicalDevices || !vkGetPhysicalDeviceProperties || !vkGetPhysicalDeviceFeatures) {
    Line(out, "devices=0");
    if (vkDestroyInstance) vkDestroyInstance(inst, nullptr);
    return out;
  }

  uint32_t n = 0;
  vkEnumeratePhysicalDevices(inst, &n, nullptr);
  std::vector<VkPhysicalDevice> devs(n);
  if (n) vkEnumeratePhysicalDevices(inst, &n, devs.data());
  Line(out, "devices=%u", n);

  for (uint32_t i = 0; i < n; ++i) {
    VkPhysicalDeviceProperties pr{};
    vkGetPhysicalDeviceProperties(devs[i], &pr);
    VkPhysicalDeviceFeatures ft{};
    vkGetPhysicalDeviceFeatures(devs[i], &ft);
    Line(out, "d%u.name=%s", i, pr.deviceName);
    Line(out, "d%u.type=%s", i, TypeName(pr.deviceType));
    Line(out, "d%u.api=%u.%u.%u", i, VK_VERSION_MAJOR(pr.apiVersion), VK_VERSION_MINOR(pr.apiVersion),
         VK_VERSION_PATCH(pr.apiVersion));
    Line(out, "d%u.driver=%u", i, pr.driverVersion);
    Line(out, "d%u.vendor=0x%04x", i, pr.vendorID);
    for (const auto& f : kFeatures) Line(out, "d%u.f.%s=%d", i, f.name, ft.*(f.member) ? 1 : 0);

    int swapchain = 0;
    if (vkEnumerateDeviceExtensionProperties) {
      uint32_t ne = 0;
      vkEnumerateDeviceExtensionProperties(devs[i], nullptr, &ne, nullptr);
      std::vector<VkExtensionProperties> ext(ne);
      if (ne) vkEnumerateDeviceExtensionProperties(devs[i], nullptr, &ne, ext.data());
      for (const auto& e : ext)
        if (!strcmp(e.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) swapchain = 1;
    }
    Line(out, "d%u.swapchain=%d", i, swapchain);

    int gfxcompute = 0;
    if (vkGetPhysicalDeviceQueueFamilyProperties) {
      uint32_t nq = 0;
      vkGetPhysicalDeviceQueueFamilyProperties(devs[i], &nq, nullptr);
      std::vector<VkQueueFamilyProperties> q(nq);
      if (nq) vkGetPhysicalDeviceQueueFamilyProperties(devs[i], &nq, q.data());
      for (const auto& f : q)
        if ((f.queueFlags & VK_QUEUE_GRAPHICS_BIT) && (f.queueFlags & VK_QUEUE_COMPUTE_BIT)) gfxcompute = 1;
    }
    Line(out, "d%u.gfxcompute=%d", i, gfxcompute);

    unsigned long long local_mb = 0;
    if (vkGetPhysicalDeviceMemoryProperties) {
      VkPhysicalDeviceMemoryProperties mp{};
      vkGetPhysicalDeviceMemoryProperties(devs[i], &mp);
      for (uint32_t h = 0; h < mp.memoryHeapCount; ++h)
        if (mp.memoryHeaps[h].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) local_mb += mp.memoryHeaps[h].size >> 20;
    }
    Line(out, "d%u.localmb=%llu", i, local_mb);
  }
  if (vkDestroyInstance) vkDestroyInstance(inst, nullptr);
  // The library stays loaded on purpose: some drivers crash when dlclose()d.
  return out;
}

}  // namespace ng2
