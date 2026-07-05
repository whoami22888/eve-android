"""
eve/adapters/android_computer.py
=================================
Thin Python adapter that bridges to the Kotlin VirtualComputer singleton via
Chaquopy's `jclass` JNI bridge.

Usage (from Python agents):
    from eve.adapters.android_computer import screenshot, click, typewrite, execute

All functions are thin wrappers — they delegate immediately to the Kotlin side.
"""

from java import jclass  # Chaquopy built-in

# Obtain the singleton instance that EveService.kt initialised.
# If VirtualComputer.getInstance() raises (because init() was never called),
# the import itself fails loudly rather than silently returning None.
_VirtualComputer = jclass("com.eve.agent.VirtualComputer")
_computer = _VirtualComputer.getInstance()


def screenshot():
    """Return an android.graphics.Bitmap of the current screen, or None."""
    return _computer.captureScreen()


def move_to(x: int, y: int) -> None:
    """Move the virtual cursor to (x, y) in screen coordinates."""
    _computer.moveMouse(x, y)


def click(x: int, y: int) -> None:
    """Dispatch a tap gesture at (x, y)."""
    _computer.click(x, y)


def typewrite(text: str) -> None:
    """Type [text] into the currently focused input field."""
    _computer.typeText(text)


def execute(language: str, script: str) -> str:
    """
    Execute [script] in the given [language] ("python" | "shell").
    Returns the script's stdout as a string.
    """
    return str(_computer.executeScript(language, script))


def http_get(url: str) -> str:
    """Perform a blocking HTTP GET and return the response body."""
    return str(_computer.httpGet(url))
