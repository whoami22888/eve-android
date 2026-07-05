@rem Gradle wrapper bootstrap script (Windows)
@rem
@rem Generate the real wrapper with:
@rem   cd eve-android
@rem   gradle wrapper --gradle-version 8.2
@rem
@if "%GRADLE_WRAPPER_JAR%"=="" set GRADLE_WRAPPER_JAR=%~dp0gradle\wrapper\gradle-wrapper.jar
@if not exist "%GRADLE_WRAPPER_JAR%" (
    echo ERROR: gradle-wrapper.jar not found. Run: gradle wrapper --gradle-version 8.2
    exit /b 1
)
java -jar "%GRADLE_WRAPPER_JAR%" %*
