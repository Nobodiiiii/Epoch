public final class BindingSmoke {
    public static void main(String[] args) throws Exception {
        Class.forName("org.lwjgl.glfw.GLFW$Functions");
        Class.forName("org.lwjgl.glfw.GLFWNativeWin32$Functions");
        Class.forName("org.lwjgl.glfw.GLFWNativeWGL$Functions");
        System.out.println(Class.forName("org.lwjgl.glfw.GLFW").getMethod("glfwGetVersionString").invoke(null));
        System.out.println("GLFW + NativeWin32 + NativeWGL initialized successfully");
    }
}
