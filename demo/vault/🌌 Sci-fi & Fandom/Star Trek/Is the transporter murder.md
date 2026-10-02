# Is the transporter murder?

*An essay by Maya Kepler, written at 2 AM, with a mobile developer's perspective.*

## The question

The transporter scans you, disassembles you, sends the pattern somewhere else and reassembles you there. Is the person who arrives *you*, or a very confident copy?

## The mobile developer's answer

It depends on whether it's a **move** or a **copy-then-delete**.

- If the pattern is *moved* — the same object, new location — it's like `moveDirectory` on the same disk: an atomic rename. You're fine.
- If it's *copied* and then the original is deleted, that's two operations. And anything with two operations can crash in between.

And we know it can, because there was that episode with the two Rikers.

## Conclusion

The transporter is not murder. It's a **non-atomic file move with no transaction log**. Which, frankly, is worse.

I will be taking the shuttle.
