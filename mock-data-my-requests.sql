-- =====================================================================
-- Mock data for GET /requests/my-requests
--
-- 1. Replace 'b123456' with the corp id you log in with (find/replace).
-- 2. If your JSON column is called request_details (not request_detail), rename it below.
-- Statuses use the enum names: COMPLETE / PENDING / REJECTED.
-- Request ids come from the requests sequence, so nothing collides with real rows.
-- Every mock request has a description starting with 'MOCK:' so it is easy to delete (see bottom).
-- =====================================================================

-- ---------- Reference data (skipped if these ids already exist) ----------
insert into request_types (id, workflow, active) values
    (1, 'Create AD Group', true)
on conflict (id) do nothing;

insert into request_workflow_steps (id, request_type_id, step_num, step) values
    (1, 1, 1, 'Submit'),
    (2, 1, 2, 'Admin Approval'),
    (3, 1, 3, 'Send to another app'),
    (4, 1, 4, 'App Completion'),
    (5, 1, 5, 'Sync')
on conflict (id) do nothing;


-- ---------- 1. Pending Admin Approval (1 day ago) ----------
with r as (
    insert into requests (request_type_id, created_by_id, created_date, updated_date, environment_id, request_detail, is_complete)
    values (1, 'b123456', (now() at time zone 'utc') - interval '1 day', (now() at time zone 'utc') - interval '1 day', 1,
            '{"site": "ent", "groupName": "finance_readers", "groupRole": "Viewer", "description": "MOCK: pending admin approval", "environment": "tableauUAT", "generatedGroupName": "lrestricted_ap123456_np_ent_finance_readers_viewer"}',
            false)
    returning id, created_date
)
insert into request_workflows (request_id, request_workflow_step_id, updated_by_userid, created_date, status, notes)
select r.id, v.step_id, v.user_id, r.created_date, v.status, v.notes
from r, (values
    (1, 'b123456', 'COMPLETE', null),
    (2, null,      'PENDING',  null)
) as v(step_id, user_id, status, notes);


-- ---------- 2. Pending Sync (3 days ago) ----------
with r as (
    insert into requests (request_type_id, created_by_id, created_date, updated_date, environment_id, request_detail, is_complete)
    values (1, 'b123456', (now() at time zone 'utc') - interval '3 days', (now() at time zone 'utc') - interval '2 days', 1,
            '{"site": "ent", "groupName": "hr_explorers", "groupRole": "Explorer", "description": "MOCK: pending sync", "environment": "tableauUAT", "generatedGroupName": "lrestricted_ap123456_np_ent_hr_explorers_explorer"}',
            false)
    returning id, created_date
)
insert into request_workflows (request_id, request_workflow_step_id, updated_by_userid, created_date, status, notes)
select r.id, v.step_id, v.user_id, r.created_date + v.after, v.status, v.notes
from r, (values
    (1, 'b123456', interval '0 hours',  'COMPLETE', null),
    (2, 'b654321', interval '2 hours',  'COMPLETE', 'Approved'),
    (3, 'b999999', interval '3 hours',  'COMPLETE', 'APP request #12345'),
    (4, 'b999999', interval '20 hours', 'COMPLETE', null),
    (5, null,      interval '20 hours', 'PENDING',  null)
) as v(step_id, user_id, after, status, notes);


-- ---------- 3. Complete (10 days ago) ----------
with r as (
    insert into requests (request_type_id, created_by_id, created_date, updated_date, environment_id, request_detail, is_complete)
    values (1, 'b123456', (now() at time zone 'utc') - interval '10 days', (now() at time zone 'utc') - interval '8 days', 1,
            '{"site": "am", "groupName": "sales_creators", "groupRole": "Creator", "description": "MOCK: complete", "environment": "tableauProd", "generatedGroupName": "lrestricted_ap123456_p_am_sales_creators_creator"}',
            true)
    returning id, created_date
)
insert into request_workflows (request_id, request_workflow_step_id, updated_by_userid, created_date, status, notes)
select r.id, v.step_id, v.user_id, r.created_date + v.after, v.status, v.notes
from r, (values
    (1, 'b123456', interval '0 hours',  'COMPLETE', null),
    (2, 'b654321', interval '4 hours',  'COMPLETE', 'Approved'),
    (3, 'b999999', interval '5 hours',  'COMPLETE', 'APP request #12346'),
    (4, 'b999999', interval '30 hours', 'COMPLETE', null),
    (5, 'b999999', interval '48 hours', 'COMPLETE', null)
) as v(step_id, user_id, after, status, notes);


-- ---------- 4. Rejected at Admin Approval (20 days ago) ----------
with r as (
    insert into requests (request_type_id, created_by_id, created_date, updated_date, environment_id, request_detail, is_complete)
    values (1, 'b123456', (now() at time zone 'utc') - interval '20 days', (now() at time zone 'utc') - interval '19 days', 1,
            '{"site": "ent", "groupName": "finance_readers", "groupRole": "Viewer", "description": "MOCK: rejected", "environment": "tableauUAT", "generatedGroupName": "lrestricted_ap123456_np_ent_finance_readers_viewer"}',
            false)
    returning id, created_date
)
insert into request_workflows (request_id, request_workflow_step_id, updated_by_userid, created_date, status, notes)
select r.id, v.step_id, v.user_id, r.created_date + v.after, v.status, v.notes
from r, (values
    (1, 'b123456', interval '0 hours', 'COMPLETE', null),
    (2, 'b654321', interval '1 day',   'REJECTED', 'Reason: duplicate group')
) as v(step_id, user_id, after, status, notes);


-- ---------- 5. Submitted, no workflow rows yet (today) -> NOT_STARTED ----------
insert into requests (request_type_id, created_by_id, created_date, updated_date, environment_id, request_detail, is_complete)
values (1, 'b123456', now() at time zone 'utc', now() at time zone 'utc', 1,
        '{"site": "ent", "groupName": "ops_viewers", "groupRole": "Viewer", "description": "MOCK: no workflow rows", "environment": "tableauUAT", "generatedGroupName": "lrestricted_ap123456_np_ent_ops_viewers_viewer"}',
        false);


-- ---------- 6. Old request (120 days ago) -> only with ?days=150 or more ----------
with r as (
    insert into requests (request_type_id, created_by_id, created_date, updated_date, environment_id, request_detail, is_complete)
    values (1, 'b123456', (now() at time zone 'utc') - interval '120 days', (now() at time zone 'utc') - interval '118 days', 1,
            '{"site": "ent", "groupName": "legacy_viewers", "groupRole": "Viewer", "description": "MOCK: older than 90 days", "environment": "tableauUAT", "generatedGroupName": "lrestricted_ap123456_np_ent_legacy_viewers_viewer"}',
            true)
    returning id, created_date
)
insert into request_workflows (request_id, request_workflow_step_id, updated_by_userid, created_date, status, notes)
select r.id, v.step_id, 'b999999', r.created_date + interval '1 day', 'COMPLETE', null
from r, (values (1), (2), (3), (4), (5)) as v(step_id);


-- ---------- 7. Someone else's request -> must NOT appear in your list ----------
with r as (
    insert into requests (request_type_id, created_by_id, created_date, updated_date, environment_id, request_detail, is_complete)
    values (1, 'b777777', (now() at time zone 'utc') - interval '2 days', (now() at time zone 'utc') - interval '2 days', 1,
            '{"site": "ent", "groupName": "other_users_group", "groupRole": "Viewer", "description": "MOCK: other user", "environment": "tableauUAT", "generatedGroupName": "lrestricted_ap123456_np_ent_other_users_group_viewer"}',
            false)
    returning id, created_date
)
insert into request_workflows (request_id, request_workflow_step_id, updated_by_userid, created_date, status, notes)
select r.id, v.step_id, v.user_id, r.created_date, v.status, null
from r, (values (1, 'b777777', 'COMPLETE'), (2, null, 'PENDING')) as v(step_id, user_id, status);


-- =====================================================================
-- Clean up all mock rows afterwards:
--
-- delete from request_workflows
-- where request_id in (select id from requests where request_detail ->> 'description' like 'MOCK:%');
-- delete from requests where request_detail ->> 'description' like 'MOCK:%';
-- =====================================================================
