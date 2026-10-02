> Shared code declares *what*, each platform says *how*:
>
> ```
> // commonMain
> expect class OvenThermometer() {
>     fun celsius(): Double
> }
>
> // androidMain
> actual class OvenThermometer {
>     actual fun celsius(): Double = bluetoothProbe.read()
> }
>
> // iosMain
> actual class OvenThermometer {
>     actual fun celsius(): Double = CoreBluetoothProbe.shared.read()
> }
> ```
