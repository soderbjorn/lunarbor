> With SKIE, a `StateFlow` becomes an `AsyncSequence`:
>
> ```
> struct TrackerView: View {
>     let viewModel: PizzaTrackerViewModel
>
>     var body: some View {
>         Text("ETA: \(viewModel.eta) min")
>             .task {
>                 for await state in viewModel.state {
>                     withAnimation { self.state = state }
>                 }
>             }
>     }
> }
> ```
