import test from 'node:test';
import assert from 'node:assert/strict';
const helpers = () => import('../../main/resources/migration/controller.mjs');
test('an initial rendering failure is announced and always releases working ownership', async () => {
  const { runInteraction } = await helpers();
  let working = false; let invoked = false; let announced;
  await runInteraction({start:() => { working = true; throw new Error('render failed'); },action:() => { invoked = true; },
    succeeded:() => assert.fail('not successful'),failed:error => { announced = error.message; },finished:() => { working = false; }});
  assert.equal(working,false); assert.equal(invoked,false); assert.equal(announced,'render failed');
});
test('stable action identity restores focus after success and failure, with a meaningful fallback if unavailable', async () => {
  const { runInteraction, restoreActionFocus } = await helpers();
  for (const key of [JSON.stringify(['validate','source:poller']), JSON.stringify(['revert','Item','source:number']), JSON.stringify(['copy','source:poller','digest'])])
  for (const outcome of ['success','failure']) for (const available of [true,false]) {
    let focus;
    await runInteraction({start:() => {},action:() => { if (outcome === 'failure') throw new Error('refused'); },
      succeeded:() => {},failed:() => {},finished:() => restoreActionFocus(key, {
        focusAction:value => { assert.equal(value,key); if (available) focus = 'replacement action'; return available; },
        focusResult:() => { focus = 'operation result'; }
      })});
    assert.equal(focus,available ? 'replacement action' : 'operation result');
  }
});
