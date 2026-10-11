public class RequestProgressService {
 
    private static final Logger logger = LoggerFactory.getLogger(RequestProgressService.class);
 
    private final RequestRepository requestRepository;
    private final RequestWorkflowStepRepository requestWorkflowStepRepository;
    private final RequestWorkflowRepository requestWorkflowRepository;
    private final RequestProgressMapper requestProgressMapper;
 
    @Transactional(readOnly = true)
    public List<MyRequestResponse> getMyRequests(String userId, int days) {
        if (days < 1) {
            throw new BadRequestException("days must be at least 1");
        }
 
        List<Request> requests = requestRepository.findCreatedBySince(userId, Instant.now().minus(days, ChronoUnit.DAYS));
        if (requests.isEmpty()) {
            return List.of();
        }
 
        Set<Long> requestTypeIds = requests.stream().map(Request::getRequestTypeId).collect(Collectors.toSet());
        List<Long> requestIds = requests.stream().map(Request::getId).toList();
 
        // request type id -> name, e.g. 1 -> "Create AD Group"
        Map<Long, String> requestTypeNames = requestTypeIds.stream()
                .collect(Collectors.toMap(Function.identity(),
                        id -> requestRepository.findRequestTypeName(id).orElse("Unknown")));
 
        // request type id -> its steps, in step_num order (the query orders them; groupingBy keeps that order)
        Map<Long, List<RequestWorkflowStep>> stepsByRequestType = requestWorkflowStepRepository
                .findByRequestTypeIds(requestTypeIds).stream()
                .collect(Collectors.groupingBy(RequestWorkflowStep::getRequestTypeId));
 
        // request id -> (step id -> recorded request_workflows row)
        Map<Long, Map<Long, RequestWorkflow>> recordedStepsByRequest = requestWorkflowRepository
                .findByRequestIds(requestIds).stream()
                .collect(Collectors.groupingBy(RequestWorkflow::getRequestId,
                        Collectors.toMap(RequestWorkflow::getRequestWorkflowStepId, Function.identity())));
 
        logger.info("Returning {} requests for {} created in the last {} days", requests.size(), userId, days);
 
        return requests.stream()
                .map(request -> buildRequestProgress(
                        request,
                        requestTypeNames.get(request.getRequestTypeId()),
                        stepsByRequestType.getOrDefault(request.getRequestTypeId(), List.of()),
                        recordedStepsByRequest.getOrDefault(request.getId(), Map.of())))
                .toList();
    }
 
    /** Works out each step's status and the request's overall badge, then hands the result to the mapper. */
    private MyRequestResponse buildRequestProgress(Request request,
                                                   String requestType,
                                                   List<RequestWorkflowStep> steps,
                                                   Map<Long, RequestWorkflow> recordedSteps) {
        int furthestRecordedStepNum = steps.stream()
                .filter(step -> recordedSteps.containsKey(step.getId()))
                .mapToInt(RequestWorkflowStep::getStepNum)
                .max()
                .orElse(0);
 
        List<WorkflowStepResponse> timeline = steps.stream()
                .map(step -> {
                    RequestWorkflow recordedStep = recordedSteps.get(step.getId());
                    return requestProgressMapper.toWorkflowStepResponse(
                            step, resolveStepStatus(step, recordedStep, furthestRecordedStepNum), recordedStep);
                })
                .toList();
 
        String currentStep = timeline.stream()
                .filter(step -> step.getStatus() == PENDING)
                .map(WorkflowStepResponse::getStepName)
                .findFirst()
                .orElse(null);
 
        return requestProgressMapper.toMyRequestResponse(
                request, requestType, resolveOverallStatus(request, timeline), currentStep, timeline);
    }
 
    /**
     * A recorded step takes its stored status. A step without a row is COMPLETE if a later step
     * has been recorded (it was passed), otherwise NOT_STARTED.
     */
    private static ProgressStatus resolveStepStatus(RequestWorkflowStep step,
                                                    RequestWorkflow recordedStep,
                                                    int furthestRecordedStepNum) {
        if (recordedStep != null) {
            return ProgressStatus.valueOf(recordedStep.getStatus());
        }
        return step.getStepNum() < furthestRecordedStepNum ? COMPLETE : NOT_STARTED;
    }
 
    /** Badge for the whole request: REJECTED, then COMPLETE (is_complete), then NOT_STARTED (no rows), else PENDING. */
    private static ProgressStatus resolveOverallStatus(Request request, List<WorkflowStepResponse> timeline) {
        if (timeline.stream().anyMatch(step -> step.getStatus() == REJECTED)) {
            return REJECTED;
        }
        if (request.isComplete()) {
            return COMPLETE;
        }
        if (timeline.stream().allMatch(step -> step.getStatus() == NOT_STARTED)) {
            return NOT_STARTED;
        }
        return PENDING;
    }
}


@SpringBootTest
@WithMockUser(username = RequestProgressServiceTest.USER)
class RequestProgressServiceTest {
 
    static final String USER = "b123456";
    private static final Long REQUEST_ID = 10L;
    private static final Long AD_GROUP_TYPE_ID = 1L;
    private static final Instant CREATED = Instant.parse("2026-10-01T09:00:00Z");
    private static final String REQUEST_DETAILS = """
            {"site": "ent", "groupName": "test_create_ad_group", "groupRole": "viewer",
             "description": "test description", "environment": "tableauUAT",
             "generatedGroupName": "lrestricted_ap123456_np_ent_test_create_ad_group_viewer"}
            """;
 
    private static final List<RequestWorkflowStep> AD_GROUP_STEPS = List.of(
            new RequestWorkflowStep(1L, AD_GROUP_TYPE_ID, 1, "Submit"),
            new RequestWorkflowStep(2L, AD_GROUP_TYPE_ID, 2, "Admin Approval"),
            new RequestWorkflowStep(3L, AD_GROUP_TYPE_ID, 3, "Send to another app"),
            new RequestWorkflowStep(4L, AD_GROUP_TYPE_ID, 4, "App Completion"),
            new RequestWorkflowStep(5L, AD_GROUP_TYPE_ID, 5, "Sync"));
 
    @MockitoBean
    private RequestRepository requestRepository;
    @MockitoBean
    private RequestWorkflowStepRepository requestWorkflowStepRepository;
    @MockitoBean
    private RequestWorkflowRepository requestWorkflowRepository;
 
    @Autowired
    private RequestProgressService service;
 
    @Test
    void returnsPendingWithAdminApprovalAsCurrentStep() {
        // Arrange
        when(requestRepository.findCreatedBySince(eq(USER), any(Instant.class)))
                .thenReturn(List.of(request(false, REQUEST_DETAILS)));
        when(requestRepository.findRequestTypeName(AD_GROUP_TYPE_ID)).thenReturn(Optional.of("Create AD Group"));
        when(requestWorkflowStepRepository.findByRequestTypeIds(Set.of(AD_GROUP_TYPE_ID))).thenReturn(AD_GROUP_STEPS);
        when(requestWorkflowRepository.findByRequestIds(List.of(REQUEST_ID))).thenReturn(List.of(
                row(1L, USER, "COMPLETE", null),
                row(2L, null, "PENDING", null)));
 
        // Act
        MyRequestResponse response = service.getMyRequests(USER, 90).get(0);
 
        // Assert
        assertThat(response.getRequestType()).isEqualTo("Create AD Group");
        assertThat(response.getEnvironment()).isEqualTo("tableauUAT");
        assertThat(response.getStatus()).isEqualTo(PENDING);
        assertThat(response.getCurrentStep()).isEqualTo("Admin Approval");
        assertThat(response.getSteps()).extracting(WorkflowStepResponse::getStatus)
                .containsExactly(COMPLETE, PENDING, NOT_STARTED, NOT_STARTED, NOT_STARTED);
        assertThat(response.getSteps().get(0).getActedBy()).isEqualTo(USER);
        assertThat(response.getSteps().get(2).getActedAt()).isNull();
    }
 
    @Test
    void returnsRejectedWithReasonAndNoCurrentStep() {
        // Arrange
        when(requestRepository.findCreatedBySince(eq(USER), any(Instant.class)))
                .thenReturn(List.of(request(false, REQUEST_DETAILS)));
        when(requestRepository.findRequestTypeName(AD_GROUP_TYPE_ID)).thenReturn(Optional.of("Create AD Group"));
        when(requestWorkflowStepRepository.findByRequestTypeIds(Set.of(AD_GROUP_TYPE_ID))).thenReturn(AD_GROUP_STEPS);
        when(requestWorkflowRepository.findByRequestIds(List.of(REQUEST_ID))).thenReturn(List.of(
                row(1L, USER, "COMPLETE", null),
                row(2L, "b654321", "REJECTED", "Reason: duplicate project")));
 
        // Act
        MyRequestResponse response = service.getMyRequests(USER, 90).get(0);
 
        // Assert
        assertThat(response.getStatus()).isEqualTo(REJECTED);
        assertThat(response.getCurrentStep()).isNull();
        assertThat(response.getSteps()).extracting(WorkflowStepResponse::getStatus)
                .containsExactly(COMPLETE, REJECTED, NOT_STARTED, NOT_STARTED, NOT_STARTED);
        assertThat(response.getSteps().get(1).getActedBy()).isEqualTo("b654321");
        assertThat(response.getSteps().get(1).getNotes()).isEqualTo("Reason: duplicate project");
    }
 
    @Test
    void returnsPendingWithSyncAsCurrentStep() {
        // Arrange
        when(requestRepository.findCreatedBySince(eq(USER), any(Instant.class)))
                .thenReturn(List.of(request(false, REQUEST_DETAILS)));
        when(requestRepository.findRequestTypeName(AD_GROUP_TYPE_ID)).thenReturn(Optional.of("Create AD Group"));
        when(requestWorkflowStepRepository.findByRequestTypeIds(Set.of(AD_GROUP_TYPE_ID))).thenReturn(AD_GROUP_STEPS);
        when(requestWorkflowRepository.findByRequestIds(List.of(REQUEST_ID))).thenReturn(List.of(
                row(1L, USER, "COMPLETE", null),
                row(2L, "b654321", "COMPLETE", "Approved"),
                row(3L, "b999999", "COMPLETE", "APP request #12345"),
                row(4L, "b999999", "COMPLETE", null),
                row(5L, null, "PENDING", null)));
 
        // Act
        MyRequestResponse response = service.getMyRequests(USER, 90).get(0);
 
        // Assert
        assertThat(response.getStatus()).isEqualTo(PENDING);
        assertThat(response.getCurrentStep()).isEqualTo("Sync");
        assertThat(response.getSteps().get(2).getNotes()).isEqualTo("APP request #12345");
    }
 
    @Test
    void returnsCompleteWhenRequestIsComplete() {
        // Arrange
        when(requestRepository.findCreatedBySince(eq(USER), any(Instant.class)))
                .thenReturn(List.of(request(true, REQUEST_DETAILS)));
        when(requestRepository.findRequestTypeName(AD_GROUP_TYPE_ID)).thenReturn(Optional.of("Create AD Group"));
        when(requestWorkflowStepRepository.findByRequestTypeIds(Set.of(AD_GROUP_TYPE_ID))).thenReturn(AD_GROUP_STEPS);
        when(requestWorkflowRepository.findByRequestIds(List.of(REQUEST_ID))).thenReturn(List.of(
                row(1L, USER, "COMPLETE", null),
                row(2L, "b654321", "COMPLETE", null),
                row(3L, "b999999", "COMPLETE", null),
                row(4L, "b999999", "COMPLETE", null),
                row(5L, "b999999", "COMPLETE", null)));
 
        // Act
        MyRequestResponse response = service.getMyRequests(USER, 90).get(0);
 
        // Assert
        assertThat(response.getStatus()).isEqualTo(COMPLETE);
        assertThat(response.getCurrentStep()).isNull();
        assertThat(response.getSteps()).extracting(WorkflowStepResponse::getStatus).containsOnly(COMPLETE);
    }
 
    @Test
    void marksStepWithoutRowBeforeARecordedStepAsComplete() {
        // Arrange
        when(requestRepository.findCreatedBySince(eq(USER), any(Instant.class)))
                .thenReturn(List.of(request(false, REQUEST_DETAILS)));
        when(requestRepository.findRequestTypeName(AD_GROUP_TYPE_ID)).thenReturn(Optional.of("Create AD Group"));
        when(requestWorkflowStepRepository.findByRequestTypeIds(Set.of(AD_GROUP_TYPE_ID))).thenReturn(AD_GROUP_STEPS);
        when(requestWorkflowRepository.findByRequestIds(List.of(REQUEST_ID))).thenReturn(List.of(
                row(1L, USER, "COMPLETE", null),
                row(3L, "b999999", "PENDING", null)));
 
        // Act
        MyRequestResponse response = service.getMyRequests(USER, 90).get(0);
 
        // Assert
        assertThat(response.getSteps()).extracting(WorkflowStepResponse::getStatus)
                .containsExactly(COMPLETE, COMPLETE, PENDING, NOT_STARTED, NOT_STARTED);
        assertThat(response.getSteps().get(1).getActedBy()).isNull();
    }
 
    @Test
    void leavesEnvironmentOutWhenRequestDetailsIsUnreadable() {
        // Arrange
        when(requestRepository.findCreatedBySince(eq(USER), any(Instant.class)))
                .thenReturn(List.of(request(false, "{not json")));
        when(requestRepository.findRequestTypeName(AD_GROUP_TYPE_ID)).thenReturn(Optional.of("Create AD Group"));
        when(requestWorkflowStepRepository.findByRequestTypeIds(Set.of(AD_GROUP_TYPE_ID))).thenReturn(AD_GROUP_STEPS);
        when(requestWorkflowRepository.findByRequestIds(List.of(REQUEST_ID))).thenReturn(List.of(
                row(1L, USER, "COMPLETE", null),
                row(2L, null, "PENDING", null)));
 
        // Act
        MyRequestResponse response = service.getMyRequests(USER, 90).get(0);
 
        // Assert
        assertThat(response.getEnvironment()).isNull();
        assertThat(response.getStatus()).isEqualTo(PENDING);
    }
 
    @Test
    void returnsEmptyListWhenUserHasNoRequests() {
        // Arrange
        when(requestRepository.findCreatedBySince(eq(USER), any(Instant.class))).thenReturn(List.of());
 
        // Act
        List<MyRequestResponse> response = service.getMyRequests(USER, 400);
 
        // Assert
        assertThat(response).isEmpty();
    }
 
    @Test
    void throwsBadRequestWhenDaysIsBelowOne() {
        // Arrange
        int days = 0;
 
        // Act + Assert
        assertThatThrownBy(() -> service.getMyRequests(USER, days))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("days must be at least 1");
    }
 
    private static Request request(boolean complete, String requestDetails) {
        return Request.builder()
                .id(REQUEST_ID)
                .requestTypeId(AD_GROUP_TYPE_ID)
                .createdById(USER)
                .createdDate(CREATED)
                .updatedDate(CREATED)
                .environmentId(1)
                .requestDetails(requestDetails)
                .complete(complete)
                .build();
    }
 
    private static RequestWorkflow row(Long stepId, String user, String status, String notes) {
        return new RequestWorkflow(REQUEST_ID, stepId, user, CREATED, status, notes);
    }
}
