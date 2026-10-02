> ```
> *=$c000
> loop  inc $d020   ; border colour
>       jmp loop
> ```
> Start it with `SYS 49152`. Stop it by turning the computer off. Or crying.
