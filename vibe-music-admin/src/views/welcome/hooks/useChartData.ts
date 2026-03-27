import {
  getAllUsersCount,
  getAllArtistsCount,
  getAllSongsCount,
  getAllPlaylistsCount
} from "@/api/data";
import { onMounted, ref } from "vue";
import { message } from "@/utils/message";
import type { Result } from "@/api/system";

const toCount = (response?: Result) => {
  if (!response || Number(response.code) !== 0) {
    return 0;
  }

  const dataValue =
    typeof response.data === "object" &&
    response.data !== null &&
    "data" in response.data
      ? (response.data as { data: unknown }).data
      : response.data;
  if (typeof dataValue === "number") {
    return dataValue;
  }
  if (typeof dataValue === "string") {
    const numericValue = Number(dataValue);
    return Number.isNaN(numericValue) ? 0 : numericValue;
  }
  if (
    typeof dataValue === "object" &&
    dataValue !== null &&
    "value" in dataValue
  ) {
    const numericValue = Number((dataValue as { value: unknown }).value);
    return Number.isNaN(numericValue) ? 0 : numericValue;
  }
  if (
    typeof dataValue === "object" &&
    dataValue !== null &&
    "count" in dataValue
  ) {
    const numericValue = Number((dataValue as { count: unknown }).count);
    return Number.isNaN(numericValue) ? 0 : numericValue;
  }
  if (
    typeof dataValue === "object" &&
    dataValue !== null &&
    "total" in dataValue
  ) {
    const numericValue = Number((dataValue as { total: unknown }).total);
    return Number.isNaN(numericValue) ? 0 : numericValue;
  }

  return 0;
};

export default () => {
  const userCount = ref<number>(0);
  const artistCount = ref<number>(0);
  const songCount = ref<number>(0);
  const playlistCount = ref<number>(0);

  const westernPopCount = ref<number>(0);
  const chinesePopCount = ref<number>(0);
  const cantonesePopCount = ref<number>(0);
  const koreanPopCount = ref<number>(0);
  const classicCount = ref<number>(0);
  const hiphopCount = ref<number>(0);
  const rockCount = ref<number>(0);
  const electronicCount = ref<number>(0);
  const jazzCount = ref<number>(0);
  const lightCount = ref<number>(0);

  const countAmerica = ref<number>(0);
  const countChina = ref<number>(0);
  const countKorea = ref<number>(0);
  const countJapan = ref<number>(0);
  const countGermany = ref<number>(0);
  const countBritain = ref<number>(0);

  const maleCount = ref<number>(0);
  const femaleCount = ref<number>(0);

  const songTypes = [
    "欧美流行",
    "华语流行",
    "粤语流行",
    "韩国流行",
    "古典",
    "嘻哈说唱",
    "摇滚",
    "电子",
    "节奏布鲁斯",
    "轻音乐"
  ];

  const artistAreas = ["美国", "中国", "韩国", "日本", "德国", "英国"];
  const artistGenders = [0, 1];

  const assignSettledCount = (
    result: PromiseSettledResult<Result>,
    target: { value: number }
  ) => {
    if (result.status !== "fulfilled") {
      console.error("统计请求失败:", result.reason);
      target.value = 0;
      return;
    }

    target.value = toCount(result.value);
  };

  const resetCounts = () => {
    userCount.value = 0;
    artistCount.value = 0;
    songCount.value = 0;
    playlistCount.value = 0;
    westernPopCount.value = 0;
    chinesePopCount.value = 0;
    cantonesePopCount.value = 0;
    koreanPopCount.value = 0;
    classicCount.value = 0;
    hiphopCount.value = 0;
    rockCount.value = 0;
    electronicCount.value = 0;
    jazzCount.value = 0;
    lightCount.value = 0;
    countAmerica.value = 0;
    countChina.value = 0;
    countKorea.value = 0;
    countJapan.value = 0;
    countGermany.value = 0;
    countBritain.value = 0;
    maleCount.value = 0;
    femaleCount.value = 0;
  };

  const fetchSummaryCounts = async () => {
    try {
      const summaryResults = await Promise.allSettled([
        getAllUsersCount(),
        getAllArtistsCount(),
        getAllSongsCount(),
        getAllPlaylistsCount()
      ]);

      assignSettledCount(summaryResults[0], userCount);
      assignSettledCount(summaryResults[1], artistCount);
      assignSettledCount(summaryResults[2], songCount);
      assignSettledCount(summaryResults[3], playlistCount);
    } catch (error) {
      console.error("获取首页总数失败:", error);
      message("会话过期，请重新登录", { type: "error" });
      userCount.value = 0;
      artistCount.value = 0;
      songCount.value = 0;
      playlistCount.value = 0;
    }
  };

  const fetchChartCounts = async () => {
    try {
      const chartResults = await Promise.allSettled([
        ...songTypes.map(type => getAllSongsCount(type)),
        ...artistAreas.map(area => getAllArtistsCount(undefined, area)),
        ...artistGenders.map(gender => getAllArtistsCount(gender))
      ]);

      const counts = [
        westernPopCount,
        chinesePopCount,
        cantonesePopCount,
        koreanPopCount,
        classicCount,
        hiphopCount,
        rockCount,
        electronicCount,
        jazzCount,
        lightCount,
        countAmerica,
        countChina,
        countKorea,
        countJapan,
        countGermany,
        countBritain,
        maleCount,
        femaleCount
      ];

      chartResults.forEach((result, index) => {
        assignSettledCount(result, counts[index]);
      });
    } catch (error) {
      console.error("获取图表统计失败:", error);
    }
  };

  onMounted(() => {
    fetchSummaryCounts();
    fetchChartCounts();
  });

  return {
    userCount,
    artistCount,
    songCount,
    playlistCount,
    westernPopCount,
    chinesePopCount,
    cantonesePopCount,
    koreanPopCount,
    classicCount,
    hiphopCount,
    rockCount,
    electronicCount,
    jazzCount,
    lightCount,
    countAmerica,
    countChina,
    countKorea,
    countJapan,
    countGermany,
    countBritain,
    maleCount,
    femaleCount
  };
};
